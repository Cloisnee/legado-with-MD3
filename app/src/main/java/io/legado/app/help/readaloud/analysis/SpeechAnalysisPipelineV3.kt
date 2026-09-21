package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.CharacterRecord
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * 分析管线 V3 —— 复刻自研朗读脚本（重构 1.4.9.x）四阶段流程（第三阶段已按甲方要求去除）：
 *
 *  触发：调度器按“预加载窗口”驱动；连续性（上一章已分析）决定第1阶段是否走 AI 选号。
 *  A 话语分析：本地规则 v2（新书首章 / 非连续章 / 队列空 / AI失败 → 快速路径）或 AI 选号
 *    （〖第N段〗+[n] 编号；校验复刻=段号/区间/越界/重叠全量收集→failHint 顺延重试；装配=按号截原文、零改写）。
 *  B 归属+人物：必须走 AI。入参=前情提要+本章〖NN〗〔〕+后续剧情（按完整段落取、上限可配、无人物表）；
 *    校验复刻（seq 全覆盖、characters 归一化、裸词/特殊词校准、默认男/男青年、路人清别名、本地归一）；
 *    失败不降级旁白：话语改由 默认对话(duihuaA/B) 发声。
 *  D 历史对比：快速命中（名/别名精确）→ 回跳命中 → 同性别±1年龄候选 + 长文本 AI 同判 → 合并（别名并、类型升级）
 *    或新建（路人=主名+【第N章】）。
 *  情绪：独立队列与第2阶段并发；第4阶段完成后最多再等 joinTimeout，超时先落库（无情绪剧本）。
 *  声线分配：三池（核心/路人/特殊）按 性别/年龄类键 未用优先随机 + 耗尽清锁重来再随机；只吃“已选中”声线库。
 *    无候选时复刻原脚本兜底链：特殊→核心·青年 → 默认对话(duihuaA/B)；全缺则留空并记日志（B10.5·Q1）。
 */
class SpeechAnalysisPipelineV3(
    private val chapterSpeechGateway: ChapterSpeechGateway,
    private val aiModels: AiModelRepository,
    private val ai: AiSpeechClient,
    private val configStore: AnalysisConfigStore,
    private val dataRepository: ReadAloudDataRepository,
) {

    companion object {
        const val RESOLVER_VERSION = "v3-script-1"

        /** 内置默认提示词（「AI 分析设置」页载入编辑器用）；key: stage1/stage2/stage4/emotion。
         *  stage4 含 %ROLE% 占位（角色名）、情绪含 %VOCAB% 占位（情绪词表），运行时自动替换。 */
        fun defaultPrompt(key: String): String = when (key) {
            "stage1" -> DEFAULT_STAGE1_PROMPT
            "stage2" -> DEFAULT_STAGE2_PROMPT
            "stage4" -> DEFAULT_STAGE4_HEAD
            else -> DEFAULT_EMOTION_PROMPT
        }

        private val EMOTIONS = listOf("平静", "愉快", "悲伤", "愤怒", "恐惧", "惊讶", "厌恶", "严肃", "温柔", "鄙夷")

        /** 裸类属词库（脚本 BARE_CLASS_WORDS，另有外挂 bare_words.json 合流） */
        private val BARE_WORDS = setOf(
            "你", "我", "他", "她", "祂", "它", "有人", "一人", "众人", "大家", "路人", "陌生人", "男人", "女人", "男孩", "女孩",
            "少年", "少女", "青年", "老人", "孩子", "大人", "姑娘", "小伙子", "老太太", "老先生", "先生", "女士", "大哥", "大姐",
            "大嫂", "老板", "掌柜的", "店小二", "师父", "师傅", "师兄", "师弟", "师姐", "师妹", "小姐", "公子", "夫人", "老爷",
            "少爷", "丫鬟", "侍卫", "士兵", "捕快", "书生", "郎中", "车夫", "猎人", "樵夫", "将军", "王爷", "公主", "太子",
            "陛下", "太监", "宫女", "嬷嬷", "道长", "和尚", "尼姑", "掌柜", "小二",
        )

        /** 特殊词库（脚本 SPECIAL_CLASS_WORDS，另有外挂 special_words.json 合流） */
        private val SPECIAL_WORDS = setOf(
            "系统", "系统提示", "系统消息", "系统公告", "系统音", "主系统", "智能ai", "人工智能", "ai助手", "语音助手", "虚拟助手",
            "机器人", "机械音", "电子音", "合成音", "广播", "喇叭", "扬声器", "通知", "公告", "提示音", "邮件", "电子邮件", "短信",
            "讯息", "留言", "弹幕", "字幕", "画外音", "智脑", "主脑", "光脑", "中央电脑", "电脑", "终端", "通讯器", "通讯仪", "全息投影",
        )

        private val DEFAULT_STAGE1_PROMPT = """
你是一名专业的小说文本话语标注员。输入文本已按片段编号，格式为 [序号] 内容。你的任务：通读全文，找出所有属于话语的片段，输出编号区间。
一、核心判断标准（先记住这一条）
话语 = 有意识主体用语言形式直接表达的具体内容，必须包含有语义的文字。
判断一个片段是否属于话语，唯一需要回答的问题是：这个片段里，有没有角色（或拟人化主体）正在“说话”或“心里说话”？如果答案是“有”，就标注；如果只是叙述者在描述、解释、概括，或者只是声音、标点，就不标。

二、话语包括哪些类型
1. 有声话语：角色说出口的话，包含对白、独白、喊叫、语言性拟声词（如“啊——！”“唉”）。但注意，动物叫声、物体声音不算。
2. 无声话语：
- 内心独白：角色在心里想但未说出口的具体话语，短促、口语化，符合角色性格，如“完了”“这地方不对劲”。这是网文难点，见第三条专门规则。
- 书面文字：书信、纸条、日记、微信、弹幕、邮件等以角色口吻写出的内容。

三、内心独白判定规则
内心独白是角色在心里“说”的话，不是叙述者对角色心理的概括。
判定方法：可发声测试
把疑似片段加上双引号，想象角色直接说出这句话：如果通顺、像人话、符合角色口吻，就是内心独白；如果像是叙述者在解释状态、形容情绪、做总结，就不是内心独白。
典型正例（是内心独白）：①完了，这地方不对劲。②该不会真有脏东西吧？③死渣男，还敢装无辜。④不对，这脚印是新的。
典型反例（不是内心独白，是心理描写或旁白）：①他感到害怕。②他心中一惊。③他陷入了绝望。④这让他很沮丧。⑤他的后背全是冷汗。
注意：内心独白可以没有“心想”“暗想”等引导词，直接融入叙述。不要因为一个短句没有引号就忽略它；也不要因为有“心想”就把后面所有内容都当话语，后面可能跟着叙述者的概括。

四、明确排除（严禁标注）
1. 叙述者的旁白、解释、评论、心理概括。例：他感到很害怕 → 不是话语。
2. 非语言性拟声词：动物叫声（汪汪、喵）、自然声（哗啦、轰隆）、物体声（砰、咔嚓）、机械声等。例：大狗咆哮：“汪汪汪！” → “汪汪汪”不是话语。
3. 纯标点片段，即使被引号包裹。例：张三：“……” → 不是话语。
4. 引导语片段，如“他说：”“心想：”“上面写着：”等，本身不是话语，即使紧邻话语也不标。

五、标注规则
1. 逐片段检查：该片段是否包含角色直接表达的具体语言文字？是则标，否则不标。
2. 连续相邻的话语片段合并为一个区间 [起始,结束]；单个片段写 [n] 或 [n,n]。
3. 按段落输出，无话语的段落直接省略。
4. 区间按编号顺序排列，互不重叠。
5. 不确定时：只标注能通过“可发声测试”的片段；测试不通过的一律不标。

六、输出格式
只输出纯 JSON，格式如下：{"段落":[{"段号":1,"话语":[[2,2]]},{"段号":2,"话语":[[1,5],[9,9]]}]}
不要输出任何解释、注释或多余文字。

七、示例（务必对照学习）
示例1：对白+引导语排除
片段：[1]他愣了一下，[2]说道： [3]“你今天必须给我回去吃饭” [4]雨还在下。
输出：{"段落":[{"段号":1,"话语":[[3,3]]}]}
示例2：内心独白无引导词，连续片段
片段：[1]他脚步一顿。 [2]不对， [3]这脚印是新的。 [4]他抬头看向前方。
输出：{"段落":[{"段号":1,"话语":[[2,3]]}]}
示例3：心理描写，不是话语
片段：[1]他心里很害怕， [2]后背全是冷汗， [3]腿也软了。
输出：{}
示例4：内心独白+心理描写混合
片段：[1]完了， [2]这次死定了。 [3]他感到一阵绝望。
输出：{"段落":[{"段号":1,"话语":[[1,2]]}]}
（[3]是心理描写，不是话语）
示例5：非语言拟声排除
片段：[1]大狗咆哮： [2]“汪汪汪！”
输出：{}
示例6：纯标点排除
片段：[1]张三： [2]“……”
输出：{}
示例7：书面文字
片段：[1]他打开信， [2]上面写着： [3]今晚八点，[4]老地方见。
输出：{"段落":[{"段号":1,"话语":[[3,4]]}]}
示例8：语言性拟声词
片段：[1]她突然 [2]“啊——” [3]地叫了一声。
输出：{"段落":[{"段号":1,"话语":[[2,2]]}]}
示例9：带“心想”但后面是概括
片段：[1]他心里想： [2]这次可能真的要失败。 [3]这让他很沮丧。
输出：{"段落":[{"段号":1,"话语":[[2,2]]}]}
（[3]是叙述者评价，不是话语）

八、最后一道检查（防幻觉）
在输出前，对每个拟标注的区间做一次快速自检：
- 这个区间里的文字，是角色说出来的话或心里说出来的话吗？
- 如果把它用双引号括起来，像角色在说话吗？
- 它包含具体的字词句，而不是只有声音或标点吗？
三个问题都回答“是”，才保留；否则删除。
""".trimIndent()

        private val DEFAULT_STAGE2_PROMPT = """
你是一个小说角色分析专家。下面的小说文本中，〖01〗〖02〗等为话语编号，紧随其后的〔〕内是该话语的原文内容；未编号部分为旁白叙述。

=== 步骤1：识别对话【序号】所对应的说话人 ===
通读全文，了解故事情节、逻辑后，精准识别所有对话【序号】所对应的说话人。
对话若有引导词（"XX说"）、动作描写、称呼指向等线索，优先遵循。若无明确线索，根据前后文、对话内容、语境、人物立场/关系推断。
输出格式：对话序号 → 说话人指称（保留原文形式）。

=== 步骤2：说话人实体归一化（同指合并） ===
基于完整章节的上下文，将步骤1输出的所有说话人指称中【指向同一真实实体】的项合并为一个角色。
合并后，每个角色仅保留一条记录，并确定其主名（必须来自原文，禁止自行创造、总结，禁止使用'你我他她它祂'作为主名）：
  - 若该实体有专名（人名、绰号、姓+身份/职业/亲属称谓），以专名为主名。
  - 若无专名，取当前章节中【最完整、最具辨识度】的指称短语为主名（如'穿黑衣的男子'优于'男子'）。
输出格式：唯一角色列表，每项包含【主名】和【该角色在本章中的所有指称别名列表】。

=== 步骤3：确定每个角色的类型（roletype）===
【强提醒】：只允许三个值：核心/路人/特殊
【判定铁律】：优先检查主名是否具有明确的特指化标识（姓氏、排行、修饰语、专名）。
1. 核心（特指个体）：满足任意一条即可。
   a) 主名含常见姓氏且后接身份/职业/亲属称谓（张妈、王秘书、李老师），或"老/小/大+姓"（老李、小张）；
   b) 主名含排行修饰（大舅、二姨、老三）；
   c) 主名含描述性修饰语（高个子的女生、我的秘书、站在门口的人）；
   d) 主名为专有名词/绰号（张三、独眼龙）；
   e) 作为独立角色出场的非人存在：有名字或有人格的灵兽、器灵、鬼魂、心魔、第二人格、NPC等。
2. 路人（泛指）：满足任意一条即可。
   a) 裸类属名词且未被姓氏/排行/修饰附着（农民、女人、妈妈、声音、老太太、秘书、皇帝、陛下）；
   b) 数量词/不定指词/指示代词（有人、几位弟子、那个秘书、你、我、他）；
   c) 仅单个姓氏且上下文未锚定具体个人；
   d) 不带姓氏的固化复合词（老板娘）。
   此类角色若需区分多个同类个体，用"弟子A/弟子B"式区分，仍归路人。
3. 特殊：话语由非人来源生成且向人类传递可理解语义，如系统提示音、游戏/直播系统音、邮件/短信/聊天消息的内容本身、智能程序/机器人/机械合成音的播报、广播喇叭通知。动物的无语义叫声不属于任何类，该话语忽略。
铁律：带姓氏的称谓必须核心；主名为"系统/广播/邮件"一类信息源词必须特殊。

=== 步骤4：确定每个角色的完整信息 ===
对于步骤3输出的每个角色，补充以下字段：
1. name（角色名称）：
   - 若 role_type 为 路人：直接取主名中的【裸类属名词】核心词（去掉数量词、指示词等）。若存在多个同类裸词，按首次出现顺序加字母后缀（如“弟子A”、“弟子B”）。
   - 若 role_type 为 核心：name 必须使用步骤2确定的【主名】原文（禁止自行创造、总结），保留姓氏、修饰、排行等所有特指化成分。例如：“张妈”、“王秘书”、“老李”、“大舅”、“高个子的女生”，不得简化或去除姓氏。
   - 若 role_type 为 特殊：name 必须使用步骤2确定的【主名】原文（禁止自行创造、总结），并且符合步骤3关于【特殊】的定义。例如：“系统”、“电视广播”、“机器人”、“人工智能”、“Ai”，不得简化。
2. aliases（别名）：
   - 若 role_type 为 路人：别名为空。
   - 若 role_type 为 核心/特殊：别名取步骤2中该角色【除主名外最常用、最具辨识度】的一个指称形式（非裸类属名词）。若没有别名，则留空。**禁止输出多个别名**，只允许最多一个“|”符号。
   - 特殊规则：若当前章节有示意某角色是冒名顶替他人（如'张三冒充李四'、'张三其实是李四假扮'），则本角色的 aliases 字段必须为空字符串，且 name 字段使用冒名者本来的名字，不得将冒名者与被冒名者视为同一角色。
3. gender（性别）：男/女，根据上下文判断，如无线索默认男。
4. age（年龄段）：核心/路人只能填 男童/少年/男青年/男中年/男老年/女童/少女/女青年/女中年/女老年，根据上下文判断，如无线索默认男青年。特殊固定填 系统。

=== 步骤5：角色列表去重 ===
检查最终需要输出的角色列表，确保没有重复实体。若有重复，以步骤2的归一化结果为准进行合并（禁止将'你我他她它祂'作为实体人物主名）。

注意：
- 特别检查：①带有姓氏的称谓（张妈、王秘书、老李等）必须归为 核心，切勿归为 路人。②输出角色的name时禁止输入旁白！

- seqmap必须覆盖文中每一个话语编号，不允许遗漏；同一人物的所有编号必须映射到同一个名称；每个被引用的人物都必须在characters中有定义。

【输出格式】只输出纯JSON：
{"seqmap":{"01":"张三","02":"张三"},"characters":[{"name":"张三","aliases":"","roletype":"核心","gender":"男","age":"男青年"}]}
""".trimIndent()

        private val DEFAULT_STAGE4_HEAD =
            "你是一个小说角色识别专家。现在给定你一份截取多个连续章节内容的小说文本，阅读完后判断「%ROLE%」是否与历史角色列表中的某个人是同一人。"

        private val DEFAULT_STAGE4_RULES = """
【判断标准】
应当判定为不同人（满足任意一条即可）：
1. 场景/空间矛盾：两者同时出现在同一场景，被描述为不同个体；或历史角色被困于无法离开的场景而新角色在另一场景活跃。
2. 冒名顶替：文本明确示意A是B冒名顶替的，判定A和B非同一人。
3. 属性矛盾：身份、职业或从属组织明显不一致且无转职说明。
4. 年龄差≥2（男童/女童=1，少年/少女=2，男青年/女青年=3，男中年/女中年=4，男老年/女老年=5），判定非同一人。
5. 时间线/生命状态矛盾：一人已死另一人仍活跃；或互动关系时间线冲突。
6. 缺乏证据：历史角色在长文本中完全未出现且无任何线索关联，默认不同人。

应当判定为同一人（满足任意一条即可）：
1. 文本有类似描述：A就是B、A即B、A原名B、A叫做B、A外号B等。
2. 从年龄差≤1、行为逻辑、外貌性格语言特征、身份职业、互动对象等多维度综合判断高度匹配。
3. 特定语境下只有一个角色可能拥有该称呼。
4. 并置揭示：叙述中将历史角色与当前称呼直接并置讨论（如：众人所说的X正是他），此时必须判同一。
5. 称呼链延续：文本显示同一实体在不同时期被不同称呼指代（幼年小名→成年大名）。
6. 唯一语境：该场景/该关系下只有一个历史角色可能对应此称呼。

无论结论如何，你必须先在心里找到至少一处具体文本依据；找不到依据时才允许判false。
注意：历史角色的别名也指代该人物。若既满足同一人又满足非同一人条件，以非同一人为准。若不满足上述所有标准，以非同一人为准。
如果判定为true，main_name必须是候选列表中的主名。
""".trimIndent()

        private val DEFAULT_EMOTION_PROMPT = """
你是一个小说对话情绪分析专家。下面的文本中，〖01〗〖02〗等为话语编号，紧随其后的〔〕内是该话语的原文内容，未编号部分为旁白叙述。
任务：对每条编号话语，判断说话人发出这句话时的【真实情绪/发声状态】。注意：字面情绪可能与真实情绪相反（反话、冷嘲、压抑、强装镇定、笑里藏刀等），有反差线索时优先判定隐藏情绪；无线索按正常表达判定。
情绪词必须严格从以下词表选择：%VOCAB%。拿不准时选“平静”。
输出：纯JSON，格式：{"emotions":{"01":"愤怒","02":"悲伤"}}，键为话语编号、值为情绪词，必须覆盖文本中的每一个编号，禁止输出任何其他文字。
""".trimIndent()
    }

    // ---------------- 数据小结构 ----------------

    private val pipelineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> AppLog.putAnalysis("分析V3·后台任务异常: ${e.localizedMessage}", e) },
    )

    private data class TextUnit(val start: Int, val end: Int, val text: String)
    private data class ParaUnits(val paraIndex: Int, val units: List<TextUnit>)
    private data class SpRange(val para: Int, val start: Int, val end: Int)
    private data class Stage2Payload(val seqMap: Map<Int, String>, val chars: List<Calibrated>)

    private class Calibrated(
        var name: String,
        var alias: String,
        var roleType: String = "",
        var gender: String = "",
        var age: String = "",
    ) {
        val rawKey: String = name
        var minSeq: Int = 99999
    }

    private val bareExtraSet: Set<String> by lazy { loadWordSet("bare_words.json") }
    private val specialExtraSet: Set<String> by lazy { loadWordSet("special_words.json") }

    // ---------------- 主流程 ----------------

    suspend fun run(
        bookUrl: String,
        bookName: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        prevChapterText: String,
        nextChapterText: String,
        force: Boolean = false,
    ): ChapterSpeechAnalysisResult? = withContext(Dispatchers.IO) {
        if (paragraphs.isEmpty()) return@withContext null
        val contentHash = SpeechIdentity.chapterContentHash(paragraphs)
        val existing = runCatching {
            chapterSpeechGateway.getAnalysis(bookUrl, chapterIndex, contentHash, RESOLVER_VERSION)
        }.getOrNull()
        if (!force && existing != null &&
            existing.status in setOf(SpeechAnalysisStatus.Success, SpeechAnalysisStatus.Partial)
        ) {
            val cached = runCatching { chapterSpeechGateway.getSegments(existing.id) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                // 存量补写：早期批次未落文件产物；此处幂等补写（已有则跳过）
                if (bookName.isNotBlank() && !dataRepository.hasChapterScript(bookName, chapterIndex)) {
                    writeFileArtifacts(bookName, chapterIndex, bookUrl, cached)
                    AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】缓存命中，已补写剧本文件")
                }
                return@withContext ChapterSpeechAnalysisResult(existing, cached, true)
            }
        }
        // ---- B8.3：DB 未命中 → 本地剧本文件回填（与播放侧同构）；命中即免重析 ----
        if (!force) {
            restoreFromScriptFiles(bookUrl, bookName, chapterIndex, paragraphs)?.let {
                return@withContext it
            }
        }
        val lockedOld = runCatching {
            existing?.let { chapterSpeechGateway.getSegments(it.id) }
        }.getOrNull().orEmpty().filter { it.userLocked }
        val lockedByKey = lockedOld.associateBy { it.paragraphIndex to it.text }

        val cfg = configStore.load()
        val records0 = dataRepository.loadBookRecords(bookName)
        val lastCh = dataRepository.lastAnalyzedChapter(bookName)
        val isContinuous = lastCh >= 0 && lastCh == chapterIndex - 1
        AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】开始：连续=$isContinuous 段数=${paragraphs.size}")

        // ===== A 话语分析 =====
        val t0 = System.currentTimeMillis()
        val ranges = stageA(paragraphs, cfg, useAi = isContinuous, chapterIndex = chapterIndex)
        if (ranges.isEmpty()) {
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第1阶段】未检出话语（${System.currentTimeMillis() - t0}ms）→ 全旁白")
        }
        var segments = assemble(paragraphs, ranges)

        // ===== B 归属+人物（AI 必须）+ 情绪（并发） =====
        val dialogueSegs = segments.filter { it.roleType != SpeechRoleType.Narrator }
        val numbered = renderNumbered(segments)
        val s2Refs = runCatching { aiModels.queueRefs("stage2") }.getOrDefault(emptyList())
        val emoRefs = runCatching { aiModels.queueRefs("emotion") }.getOrDefault(emptyList())
        val emoJob = if (emoRefs.isNotEmpty() && dialogueSegs.isNotEmpty()) {
            pipelineScope.async { callEmotion(numbered, dialogueSegs.size, cfg, emoRefs, chapterIndex) }
        } else null
        var usedAi2 = false
        var entries: List<Calibrated> = emptyList()
        if (s2Refs.isNotEmpty() && dialogueSegs.isNotEmpty()) {
            val s2 = callStage2(numbered, prevChapterText, nextChapterText, cfg, dialogueSegs.size, s2Refs, chapterIndex)
            if (s2 != null) {
                usedAi2 = true
                entries = s2.chars
                segments = applyStage2(segments, s2.seqMap)
                val roleNames = entries.map { it.name }
                val roleList = if (roleNames.isEmpty()) "" else "：${joinCapped(roleNames)}"
                AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第2阶段】完成：角色 ${entries.size} 个（seq ${s2.seqMap.size}/${dialogueSegs.size}）$roleList")
            } else {
                AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第2阶段】失败：话语改用默认对话(duihuaA/duihuaB)发声")
            }
        } else if (dialogueSegs.isNotEmpty()) {
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第2阶段】跳过：未配模型队列 → 话语改用默认对话(duihuaA/duihuaB)发声")
        }

        // ===== D 历史对比 + 记录库 =====
        val t4 = System.currentTimeMillis()
        val s4Refs = runCatching { aiModels.queueRefs("stage4") }.getOrDefault(emptyList())
        val stageDResult = stageD(segments, entries, records0, chapterIndex, bookName, s4Refs, cfg)
        segments = stageDResult.first
        val recordsUpd = stageDResult.second
        AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第4阶段】完成（${System.currentTimeMillis() - t4}ms）：记录 ${recordsUpd.size} 项")

        // ===== 情绪 join（第4阶段完成后最多再等 joinTimeout；超时先落库） =====
        if (emoJob != null) {
            val emo = withTimeoutOrNull(cfg.emotionJoinTimeoutMs) { emoJob.await() }
            if (emo != null && emo.isNotEmpty()) {
                segments = applyEmotion(segments, emo)
                AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·情绪】覆盖 ${emo.size}/${dialogueSegs.size} 条")
            } else if (emo == null && emoJob.isActive) {
                AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·情绪】${cfg.emotionJoinTimeoutMs}ms 内未返回，先落库（后台结果不写回）")
            } else {
                AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·情绪】无有效结果，以无情绪剧本落库")
            }
        }

        // ===== 声线分配（三池，只吃“已选中”声线库） =====
        val recordsFin = assignVoices(recordsUpd, chapterIndex)
        if (recordsFin.isNotEmpty()) {
            dataRepository.saveBookRecords(bookName, recordsFin)
        }

        // ===== 落库 =====
        val analysisId = SpeechIdentity.analysisId(bookUrl, chapterIndex, contentHash, RESOLVER_VERSION)
        val status = if (dialogueSegs.isNotEmpty() && !usedAi2 && s2Refs.isNotEmpty()) {
            SpeechAnalysisStatus.Partial
        } else {
            SpeechAnalysisStatus.Success
        }
        val analysis = ChapterSpeechAnalysis(
            id = analysisId,
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            resolverVersion = RESOLVER_VERSION,
            characterRevision = "",
            status = status,
        )
        val finalSegs = if (lockedByKey.isNotEmpty()) {
            segments.map { lockedByKey[it.paragraphIndex to it.text] ?: it }
        } else {
            segments
        }
        val bound = finalSegs.map { s ->
            s.copy(
                id = SpeechIdentity.segmentId(analysisId, s.paragraphIndex, s.start, s.end),
                analysisId = analysisId,
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
            )
        }
        runCatching { chapterSpeechGateway.saveAnalysis(analysis, bound) }
            .onFailure { AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】落库失败: ${it.localizedMessage}", it) }
        AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】落库完成 status=${status.storageValue} 段数=${bound.size} AI=$usedAi2")
        // ===== 文件产物：all_clean_text / chapter_cache / book_rev（供角色管理/书籍管理读取） =====
        val fileOk = writeFileArtifacts(bookName, chapterIndex, bookUrl, bound)
        AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】剧本文件写入=$fileOk")
        ChapterSpeechAnalysisResult(analysis, bound, false)
    }


    /** 本地快速分段（点击朗读 → 先出声；与 A 阶段本地路径同源）：引号规则 v2 → 段落装配 */
    fun quickLocalSegments(paragraphs: List<CanonicalSpeechParagraph>): List<ChapterSpeechSegment> {
        val ranges = ArrayList<SpRange>()
        paragraphs.forEach { p ->
            QuoteSpeechRules.quoteSpans(p.text).forEach { s ->
                ranges.add(SpRange(p.index, s.first, s.last + 1))
            }
        }
        return assemble(paragraphs, ranges)
    }

    /**
     * B8.3 本地剧本文件回填：DB 未命中时，把本地剧本（`all_clean_text` / `chapter_cache`）的本章行
     * 对齐回当前正文，重建 segments 并回写 DB（同 contentHash + resolverVersion）。
     * 使「清应用数据后」朗读直接消费剧本、不再重析（调度器随后缓存命中），声线/情绪随剧本还原。
     * 对齐失败（正文已变 / 无法唯一定位）或剧本不存在 → 返回 null，调用方回落快速链。
     * [logMiss]=false 时静默未命中（播放侧 / 预下载侧的探测用，避免与管线侧重复记账，B10.4.3）。
     * B10.5·Q3 换源复用：剧本行改「候选序」读取（文件 → 精确键 → 同章任意键），逐个对齐取首个成功者；
     * 复用成功后为当前 bookUrl 补写缓存键（自愈），后续读取走精确命中。
     */
    suspend fun restoreFromScriptFiles(
        bookUrl: String,
        bookName: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        logMiss: Boolean = true,
    ): ChapterSpeechAnalysisResult? = withContext(Dispatchers.IO) {
        if (bookUrl.isBlank() || paragraphs.isEmpty()) return@withContext null
        val name = bookName.ifBlank { dataRepository.loadBookName(bookUrl) }
        if (name.isBlank()) return@withContext null
        val contentHash = SpeechIdentity.chapterContentHash(paragraphs)
        val existing = runCatching {
            chapterSpeechGateway.getAnalysis(bookUrl, chapterIndex, contentHash, RESOLVER_VERSION)
        }.getOrNull()
        if (existing != null &&
            existing.status in setOf(SpeechAnalysisStatus.Success, SpeechAnalysisStatus.Partial)
        ) {
            return@withContext null // DB 已就绪，调用方按命中处理
        }
        val candidates = dataRepository.loadChapterScriptCandidates(name, bookUrl, chapterIndex)
        if (candidates.isEmpty()) {
            if (logMiss) AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】本地剧本回填：无剧本行")
            return@withContext null
        }
        val aligned = candidates.firstNotNullOfOrNull { rows -> ScriptFileBackfill.align(paragraphs, rows) }
        if (aligned == null) {
            if (logMiss) AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】本地剧本回填跳过：剧本与正文不一致或无法唯一定位")
            return@withContext null
        }
        val analysisId = SpeechIdentity.analysisId(bookUrl, chapterIndex, contentHash, RESOLVER_VERSION)
        val segments = ScriptFileBackfill.toSegments(aligned, bookUrl, chapterIndex, analysisId)
        val analysis = ChapterSpeechAnalysis(
            id = analysisId,
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            resolverVersion = RESOLVER_VERSION,
            characterRevision = "",
            status = SpeechAnalysisStatus.Success,
        )
        val saved = runCatching { chapterSpeechGateway.saveAnalysis(analysis, segments) }
        if (saved.isFailure) {
            AppLog.putAnalysis(
                "【分析V3·第${chapterIndex + 1}章】本地剧本回填：${segments.size} 段（回写 DB 失败，本次仍可用）",
                saved.exceptionOrNull(),
            )
        } else {
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】本地剧本回填成功：${segments.size} 段（免重析）")
        }
        // B10.5·Q3 换源自愈：把复用结果落到当前 bookUrl 的缓存键（已存在则跳过）
        dataRepository.ensureChapterCacheForUrl(name, bookUrl, chapterIndex, renderScriptForStore(segments))
        ChapterSpeechAnalysisResult(analysis, segments, true)
    }

    // ---------------- A 话语分析 ----------------

    private suspend fun stageA(
        paragraphs: List<CanonicalSpeechParagraph>,
        cfg: AnalysisConfigStore.Config,
        useAi: Boolean,
        chapterIndex: Int,
    ): List<SpRange> {
        // 本地路径：引号包裹规则 v2
        val local = ArrayList<SpRange>()
        paragraphs.forEach { p ->
            QuoteSpeechRules.quoteSpans(p.text).forEach { s ->
                local.add(SpRange(p.index, s.first, s.last + 1))
            }
        }
        if (!useAi) {
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第1阶段】本地规则快速识别（首章/非连续）：${local.size} 段话语")
            return local
        }
        // AI 路径：选号标注（失败回退本地）
        val refs = runCatching { aiModels.queueRefs("stage1") }.getOrDefault(emptyList())
        if (refs.isEmpty()) {
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第1阶段】未配模型队列 → 本地规则：${local.size} 段话语")
            return local
        }
        val cands = buildParaUnits(paragraphs)
        if (cands.isEmpty()) return local
        val unitCounts = cands.associate { it.paraIndex to it.units.size }.toMutableMap()
        paragraphs.forEach { p -> if (p.index !in unitCounts) unitCounts[p.index] = 0 }
        val unitsText = buildUnitsText(cands)
        val base = cfg.stage1Prompt.ifBlank { DEFAULT_STAGE1_PROMPT }
        val promptFactory = { failHint: String ->
            buildString {
                append(base)
                if (failHint.isNotBlank()) {
                    append("\n【重要】你上一次的输出存在以下问题：").append(failHint)
                        .append("。请修正后重新输出完整JSON。\n\n")
                } else {
                    append("\n\n")
                }
                append("=== 待分析文本 ===\n").append(unitsText)
            }
        }
        val sel = ai.completeValidated(refs, "只输出 JSON。", promptFactory, cfg.maxOutputTokens, logTag = "第${chapterIndex + 1}章·第1阶段") { raw ->
            validateSelection(raw, unitCounts)
        }
        if (sel != null) {
            val fromAi = ArrayList<SpRange>()
            cands.forEach candLoop@{ c ->
                val ranges = sel[c.paraIndex + 1] ?: return@candLoop
                ranges.forEach rLoop@{ (a, b) ->
                    val u1 = c.units.getOrNull(a - 1) ?: return@rLoop
                    val u2 = c.units.getOrNull(b - 1) ?: return@rLoop
                    fromAi.add(SpRange(c.paraIndex, u1.start, u2.end))
                }
            }
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第1阶段】AI选号成功：${fromAi.size} 段话语")
            return fromAi
        }
        AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第1阶段】AI选号失败 → 回退本地规则：${local.size} 段话语")
        return local
    }

    private fun buildParaUnits(paragraphs: List<CanonicalSpeechParagraph>): List<ParaUnits> {
        return paragraphs.mapNotNull { p ->
            val units = QuoteSpeechRules.splitToUnits(p.text).map { u ->
                TextUnit(u.start, u.end, p.text.substring(u.start, u.end))
            }
            if (units.isEmpty()) null else ParaUnits(p.index, units)
        }
    }

    private fun buildUnitsText(cands: List<ParaUnits>): String = buildString {
        cands.forEach { c ->
            append("〖第").append(c.paraIndex + 1).append("段〗\n")
            c.units.forEachIndexed { i, u ->
                append("[").append(i + 1).append("] ").append(u.text).append("\n")
            }
        }
    }

    /** 选号结构校验（复刻脚本 parseAndValidateSelectionResult：全量收集错误 → failHint） */
    private fun validateSelection(
        raw: String,
        unitCounts: Map<Int, Int>,
    ): ValidateOutcome<Map<Int, List<Pair<Int, Int>>>> {
        val root = ai.extractJson(raw) ?: return ValidateOutcome(null, "返回不是JSON对象")
        val list = root.optJSONArray("段落") ?: root.optJSONArray("段") ?: root.optJSONArray("paragraphs")
            ?: return ValidateOutcome(null, "缺少段落列表字段")
        val totalParas = unitCounts.size
        val map = HashMap<Int, List<Pair<Int, Int>>>()
        val seen = HashSet<Int>()
        val errs = ArrayList<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i)
            if (item == null) {
                errs.add("存在非对象的段落条目")
                continue
            }
            val nRaw: Any? = if (item.has("段号")) item.opt("段号") else item.opt("id")
            if (nRaw == null) {
                errs.add("存在缺少段号的条目")
                continue
            }
            val n = nRaw.toString().trim().toIntOrNull()
            if (n == null) {
                errs.add("段号不是整数：「$nRaw」")
                continue
            }
            if (n < 1 || n > totalParas) {
                errs.add("段号越界：$n（全文共${totalParas}段）")
                continue
            }
            if (n in seen) {
                errs.add("段号重复：$n")
                continue
            }
            seen.add(n)
            val maxU = unitCounts[n - 1] ?: 0
            val utt: Any? = if (item.has("话语")) item.opt("话语")
            else if (item.has("utterances")) item.opt("utterances") else item.opt("选区")
            val arr = ArrayList<Pair<Int, Int>>()
            if (utt == null) {
                // 无话语
            } else if (utt is JSONArray) {
                var lastEnd = 0
                var bad = false
                for (j in 0 until utt.length()) {
                    val x = utt.opt(j)
                    var a: Int? = null
                    var b: Int? = null
                    if (x is JSONArray) {
                        a = x.opt(0).toString().toIntOrNull()
                        b = (if (x.length() > 1) x.opt(1) else x.opt(0)).toString().toIntOrNull()
                    } else {
                        a = x.toString().toIntOrNull()
                        b = a
                    }
                    if (a == null || b == null) {
                        errs.add("第${n}段存在无法解析的片段编号")
                        bad = true
                        break
                    }
                    if (a < 1 || b > maxU) {
                        errs.add("第${n}段片段编号越界：$a-$b（本段共${maxU}片）")
                        bad = true
                        break
                    }
                    if (a > b) {
                        errs.add("第${n}段区间起点大于终点：$a-$b")
                        bad = true
                        break
                    }
                    if (a <= lastEnd) {
                        errs.add("第${n}段区间重叠或未按顺序：$a-$b")
                        bad = true
                        break
                    }
                    lastEnd = b
                    arr.add(a to b)
                }
                if (bad) continue
            } else {
                errs.add("第${n}段话语字段类型非法")
                continue
            }
            if (arr.isNotEmpty()) map[n] = arr
        }
        if (errs.isNotEmpty()) return ValidateOutcome(null, errs.joinToString("；"))
        return ValidateOutcome(map)
    }

    // ---------------- 装配 ----------------

    private fun assemble(
        paragraphs: List<CanonicalSpeechParagraph>,
        ranges: List<SpRange>,
    ): List<ChapterSpeechSegment> {
        val out = ArrayList<ChapterSpeechSegment>()
        val byPara = ranges.groupBy { it.para }
        paragraphs.forEach { p ->
            val rs = (byPara[p.index] ?: emptyList()).sortedBy { it.start }
            var cursor = 0
            rs.forEach { r ->
                if (cursor < r.start) {
                    val t = p.text.substring(cursor, r.start)
                    if (t.isNotBlank()) out.add(seg(p, cursor, r.start, t, SpeechRoleType.Narrator))
                }
                val rs2 = r.start.coerceIn(0, p.text.length)
                val re2 = r.end.coerceIn(rs2, p.text.length)
                val t = p.text.substring(rs2, re2)
                if (t.isNotBlank()) {
                    val role = if (thoughtHint(p.text, rs2)) SpeechRoleType.Thought else SpeechRoleType.Character
                    out.add(seg(p, rs2, re2, t, role))
                }
                cursor = re2
            }
            if (cursor < p.text.length) {
                val t = p.text.substring(cursor)
                if (t.isNotBlank()) out.add(seg(p, cursor, p.text.length, t, SpeechRoleType.Narrator))
            }
        }
        return out
    }

    private fun seg(
        p: CanonicalSpeechParagraph,
        start: Int,
        end: Int,
        text: String,
        role: SpeechRoleType,
    ): ChapterSpeechSegment = ChapterSpeechSegment(
        id = "",
        analysisId = "",
        bookUrl = "",
        chapterIndex = 0,
        paragraphIndex = p.index,
        start = start,
        end = end,
        chapterPosition = p.chapterPosition + start,
        text = text,
        roleType = role,
        source = SpeechResolutionSource.Rule,
    )

    private fun thoughtHint(text: String, spanStart: Int): Boolean {
        val from = (spanStart - 24).coerceAtLeast(0)
        val head = text.substring(from, spanStart)
        return Regex("心想|心道|暗道|想道|暗想|默念").containsMatchIn(head)
    }

    /** 编号渲染（〖NN〗〔text〕；旁白原样；段间补换行）——发给第2阶段/情绪 */
    private fun renderNumbered(segments: List<ChapterSpeechSegment>): String {
        val sb = StringBuilder()
        var n = 0
        var lastP = -1
        segments.forEach { s ->
            if (lastP != -1 && s.paragraphIndex != lastP) sb.append("\n")
            if (s.roleType == SpeechRoleType.Narrator) {
                sb.append(s.text)
            } else {
                n++
                sb.append("〖").append(n.toString().padStart(2, '0')).append("〗〔").append(s.text).append("〕")
            }
            lastP = s.paragraphIndex
        }
        return sb.toString()
    }

    /** 前情/后续取文：按完整段落取，累计不超过 limit（0..3000），不截断段落 */
    private fun takeContextByParagraphs(text: String, limit: Int, fromTail: Boolean): String {
        if (limit <= 0 || text.isBlank()) return ""
        val paras = text.split("\n").filter { it.isNotBlank() }
        if (paras.isEmpty()) return ""
        val out = ArrayList<String>()
        var total = 0
        var i = if (fromTail) paras.size - 1 else 0
        val step = if (fromTail) -1 else 1
        while (i in paras.indices) {
            val p = paras[i]
            if (total + p.length > limit) break
            out.add(p)
            total += p.length
            i += step
        }
        return (if (fromTail) out.reversed() else out).joinToString("\n")
    }

    // ---------------- B 归属+人物 ----------------

    private suspend fun callStage2(
        numbered: String,
        prevText: String,
        nextText: String,
        cfg: AnalysisConfigStore.Config,
        expectedCount: Int,
        refs: List<AiSpeechClient.ModelRef>,
        chapterIndex: Int,
    ): Stage2Payload? {
        val prev = takeContextByParagraphs(prevText, cfg.prevLimit, fromTail = true)
        val next = takeContextByParagraphs(nextText, cfg.nextLimit, fromTail = false)
        val head = cfg.stage2Prompt.ifBlank { DEFAULT_STAGE2_PROMPT }
        val user = buildString {
            if (prev.isNotBlank()) append("【前情提要】\n").append(prev).append("\n\n")
            append("【本章正文】\n").append(numbered)
            if (next.isNotBlank()) append("\n\n【后续剧情】\n").append(next)
        }
        val promptFactory = { failHint: String ->
            buildString {
                append(head)
                if (failHint.isNotBlank()) {
                    append("\n【重要】你上一次的输出存在以下问题：").append(failHint)
                        .append("。请修正后重新输出完整JSON。\n\n")
                } else {
                    append("\n\n")
                }
                append("=== 待分析文本 ===\n").append(user)
            }
        }
        return ai.completeValidated(refs, "只输出 JSON。", promptFactory, cfg.maxOutputTokens, logTag = "第${chapterIndex + 1}章·第2阶段") { raw ->
            validateStage2(raw, expectedCount, chapterIndex)
        }
    }

    /** 第2阶段校验全复刻（⑦⑧一致性 → ⑨~⑫校准 → ⑬本地归一） */
    private fun validateStage2(raw: String, expectedCount: Int, chapterIndex: Int): ValidateOutcome<Stage2Payload> {
        val root = ai.extractJson(raw) ?: return ValidateOutcome(null, "返回不是JSON对象")
        val smObj = root.optJSONObject("seqmap") ?: root.optJSONObject("序号映射") ?: root.optJSONObject("seqMap")
            ?: return ValidateOutcome(null, "缺少seqmap")
        val sm = HashMap<Int, String>()
        val keys = smObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val n = k.trim().toIntOrNull() ?: continue
            if (n < 1) continue
            sm[n] = smObj.optString(k).trim()
        }
        val arr = ArrayList<JSONObject>()
        val rcArr = root.optJSONArray("characters") ?: root.optJSONArray("角色列表")
            ?: root.optJSONArray("characterlist") ?: root.optJSONArray("characterList")
        if (rcArr != null) {
            for (i in 0 until rcArr.length()) rcArr.optJSONObject(i)?.let { arr.add(it) }
        } else {
            val rcObj = root.optJSONObject("characters") ?: root.optJSONObject("角色列表")
            if (rcObj != null) {
                val ks = rcObj.keys()
                while (ks.hasNext()) {
                    val kk = ks.next()
                    val oo = rcObj.optJSONObject(kk) ?: JSONObject()
                    if (oo.optString("name").isBlank()) oo.put("name", kk)
                    arr.add(oo)
                }
            }
        }
        if (arr.isEmpty()) return ValidateOutcome(null, "characters为空")

        val byName = LinkedHashMap<String, Calibrated>()
        val aliasIndex = HashMap<String, String>()
        val errs = ArrayList<String>()
        arr.forEach { o ->
            val nm = o.optString("name").ifBlank { o.optString("主名") }.ifBlank { o.optString("名称") }.trim()
            if (nm.isBlank()) {
                errs.add("存在name为空的角色")
                return@forEach
            }
            var al = ""
            val alRaw: Any? = o.opt("aliases") ?: o.opt("别名")
            when (alRaw) {
                is JSONArray -> {
                    for (q in 0 until alRaw.length()) {
                        val t = alRaw.optString(q).trim()
                        if (t.isNotEmpty()) {
                            al = t
                            break
                        }
                    }
                }
                null -> Unit
                else -> al = alRaw.toString().split("|").firstOrNull()?.trim().orEmpty()
            }
            if (al == nm) al = ""
            if (byName.containsKey(nm)) {
                val cur = byName.getValue(nm)
                if (cur.alias.isBlank() && al.isNotBlank()) cur.alias = al
                return@forEach
            }
            val cal = Calibrated(
                name = nm,
                alias = al,
                roleType = o.optString("roletype").ifBlank { o.optString("type") }.ifBlank { o.optString("类型") }.trim(),
                gender = o.optString("gender").ifBlank { o.optString("性别") }.trim(),
                age = o.optString("age").ifBlank { o.optString("年龄") }.trim(),
            )
            byName[nm] = cal
            aliasIndex[nm.lowercase()] = nm
            if (al.isNotBlank()) aliasIndex[al.lowercase()] = nm
        }
        for (s in 1..expectedCount) {
            if (sm[s].isNullOrBlank()) errs.add("seqmap缺少序号${s.toString().padStart(2, '0')}")
        }
        sm.forEach { (n, v) ->
            if (v.isBlank()) {
                errs.add("seqmap序号${n.toString().padStart(2, '0')}为空")
                return@forEach
            }
            if (!byName.containsKey(v)) {
                val tgt = aliasIndex[v.lowercase()]
                if (tgt != null) {
                    AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第2阶段】seqmap校正：$v → $tgt")
                    sm[n] = tgt
                } else {
                    errs.add("seqmap中人物「$v」未在characters中定义")
                }
            }
        }
        val referenced = sm.values.filter { it.isNotBlank() }.toSet()
        byName.forEach { (name, _) ->
            if (name !in referenced) errs.add("角色「$name」未被任何序号引用")
        }
        if (errs.isNotEmpty()) return ValidateOutcome(null, errs.joinToString("；"))

        // ⑨~⑫ 校准
        val calibrated = byName.values.map { calibrate(it, chapterIndex) }
        calibrated.forEach { c ->
            if (c.rawKey != c.name) {
                sm.forEach { (n, v) -> if (v == c.rawKey) sm[n] = c.name }
            }
        }
        // ⑬ 本地归一（仅核心/特殊参与）
        val normalized = localNormalize(calibrated, sm, chapterIndex)
        return ValidateOutcome(Stage2Payload(sm.toMap(), normalized))
    }

    private fun calibrate(c: Calibrated, chapterIndex: Int): Calibrated {
        val rt = c.roleType.trim()
        c.roleType = when {
            rt == "核心" || rt == "路人" || rt == "特殊" -> rt
            isBareClassWord(c.name) -> "路人"
            isSpecialClassWord(c.name) -> "特殊"
            else -> "核心"
        }
        if (c.roleType == "路人") {
            c.alias = ""
        } else {
            if (c.alias.isNotBlank() && isBareClassWord(c.alias)) c.alias = ""
            if (isBareClassWord(c.name)) {
                if (c.alias.isNotBlank() && !isBareClassWord(c.alias)) {
                    AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第2阶段】校准：主名「${c.name}」为裸属词，别名「${c.alias}」上位为主名")
                    c.name = c.alias
                    c.alias = ""
                } else {
                    AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第2阶段】校准：主名「${c.name}」与别名均为裸属词，降级为路人")
                    c.roleType = "路人"
                }
            }
        }
        c.gender = if (c.gender == "男" || c.gender == "女") c.gender else inferGenderFromAge(c.age)
        c.age = if (c.roleType == "特殊") "系统" else standardizeAge(c.age, c.gender)
        return c
    }

    private fun localNormalize(list: List<Calibrated>, sm: MutableMap<Int, String>, chapterIndex: Int): List<Calibrated> {
        val minSeq = HashMap<String, Int>()
        sm.forEach { (n, k) ->
            if (k.isNotBlank()) {
                val cur = minSeq[k]
                if (cur == null || n < cur) minSeq[k] = n
            }
        }
        list.forEach { it.minSeq = minSeq[it.name] ?: 99999 }
        val work = ArrayList(list)
        var changed = true
        var guard = 80
        while (changed && guard-- > 0) {
            changed = false
            var i = 0
            while (i < work.size) {
                var j = i + 1
                while (j < work.size) {
                    val a = work[i]
                    val b = work[j]
                    if (a.roleType == "路人" || b.roleType == "路人") {
                        j++
                        continue
                    }
                    if (!isSamePersonEntry(a, b)) {
                        j++
                        continue
                    }
                    // 序号在前者为主名（意图复刻；原脚本此处疑似笔误，按意图实现）
                    val winner = if (b.minSeq < a.minSeq) b else a
                    val loser = if (winner === a) b else a
                    AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第2阶段】本地归一：${loser.name} 并入 ${winner.name}")
                    val toks = aliasTokensOf(winner.alias).toMutableList()
                    if (loser.name != winner.name && loser.name !in toks) toks.add(loser.name)
                    aliasTokensOf(loser.alias).forEach { t ->
                        if (t != winner.name && t !in toks) toks.add(t)
                    }
                    winner.alias = toks.distinct().joinToString("|")
                    sm.forEach { (n, v) -> if (v == loser.name) sm[n] = winner.name }
                    work.remove(loser)
                    changed = true
                }
                i++
            }
        }
        return work
    }

    private fun isSamePersonEntry(a: Calibrated, b: Calibrated): Boolean {
        if (a.name == b.name) return true
        if (b.name in aliasTokensOf(a.alias)) return true
        if (a.name in aliasTokensOf(b.alias)) return true
        return false
    }

    private fun aliasTokensOf(alias: String): List<String> =
        alias.split("|").map { it.trim() }.filter { it.isNotEmpty() }

    private fun applyStage2(
        segments: List<ChapterSpeechSegment>,
        seqMap: Map<Int, String>,
    ): List<ChapterSpeechSegment> {
        var n = 0
        return segments.map { s ->
            if (s.roleType == SpeechRoleType.Narrator) {
                s
            } else {
                n++
                val name = seqMap[n].orEmpty()
                if (name.isBlank()) {
                    s.copy(source = SpeechResolutionSource.Ai)
                } else {
                    s.copy(
                        characterName = name,
                        characterId = null,
                        source = SpeechResolutionSource.Ai,
                        confidence = 0.9f,
                    )
                }
            }
        }
    }

    // ---------------- 情绪 ----------------

    private suspend fun callEmotion(
        numbered: String,
        count: Int,
        cfg: AnalysisConfigStore.Config,
        refs: List<AiSpeechClient.ModelRef>,
        chapterIndex: Int,
    ): Map<Int, String>? {
        val head = cfg.emotionPrompt.ifBlank { DEFAULT_EMOTION_PROMPT }
            .replace("%VOCAB%", EMOTIONS.joinToString("/"))
        val promptFactory = { failHint: String ->
            buildString {
                append(head)
                if (failHint.isNotBlank()) {
                    append("\n【重要】你上一次的输出存在以下问题：").append(failHint)
                        .append("。请修正后重新输出完整JSON，仍然必须覆盖文本中的每一个编号。\n\n")
                }
                append("\n=== 待分析文本 ===\n").append(numbered)
            }
        }
        return ai.completeValidated(refs, "只输出 JSON。", promptFactory, cfg.maxOutputTokens, logTag = "第${chapterIndex + 1}章·情绪") { raw ->
            validateEmotion(raw, count)
        }
    }

    private fun validateEmotion(raw: String, count: Int): ValidateOutcome<Map<Int, String>> {
        val root = ai.extractJson(raw) ?: return ValidateOutcome(null, "返回不是JSON对象")
        val m = root.optJSONObject("emotions") ?: root.optJSONObject("情绪") ?: root.optJSONObject("seqmap")
            ?: return ValidateOutcome(null, "缺少emotions映射")
        var empty = true
        val errs = ArrayList<String>()
        val out = HashMap<Int, String>()
        val keys = m.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            empty = false
            val n = k.trim().toIntOrNull()
            if (n == null || n < 1 || n > count) {
                errs.add("存在非法编号：$k")
                continue
            }
            val v = m.optString(k).trim()
            if (v !in EMOTIONS) errs.add("编号${k}的情绪词不在词表内") else out[n] = v
        }
        if (empty) return ValidateOutcome(null, "emotions为空")
        if (errs.isNotEmpty()) return ValidateOutcome(null, errs.joinToString("；"))
        return ValidateOutcome(out)
    }

    private fun applyEmotion(
        segments: List<ChapterSpeechSegment>,
        emo: Map<Int, String>,
    ): List<ChapterSpeechSegment> {
        var n = 0
        return segments.map { s ->
            if (s.roleType == SpeechRoleType.Narrator) {
                s
            } else {
                n++
                emo[n]?.let { s.copy(emotion = it) } ?: s
            }
        }
    }

    // ---------------- D 历史对比 + 记录库 ----------------

    private suspend fun stageD(
        segments: List<ChapterSpeechSegment>,
        entries: List<Calibrated>,
        records0: List<CharacterRecord>,
        chapterIndex: Int,
        bookName: String,
        s4Refs: List<AiSpeechClient.ModelRef>,
        cfg: AnalysisConfigStore.Config,
    ): Pair<List<ChapterSpeechSegment>, List<CharacterRecord>> {
        if (entries.isEmpty()) return segments to records0
        val recs = ArrayList(records0)
        val suffix = "【第${chapterIndex + 1}章】"
        val snapshot = recs.filter { it.lastAppearanceChapter < chapterIndex }
        val hasHistory = snapshot.isNotEmpty()
        val rename = HashMap<String, String>()
        val pending = ArrayList<Calibrated>()

        fun finalNameOf(e: Calibrated): String =
            if (e.roleType == "路人" && !e.name.contains("【第")) e.name + suffix else e.name

        val hitLogs = ArrayList<String>()
        val backLogs = ArrayList<String>()
        val newLogs = ArrayList<String>()
        entries.forEach { e ->
            val hit = if (hasHistory && e.roleType != "路人") histMatch(snapshot, e) else null
            val hitAll = if (hit == null && e.roleType != "路人") histMatch(recs, e) else null
            if (hit != null) {
                val (fn, desc) = applyMerge(hit, e, finalNameOf(e), chapterIndex, bookName)
                rename[e.name] = fn
                hitLogs.add(desc)
            } else if (hitAll != null) {
                val (fn, desc) = applyMerge(hitAll, e, finalNameOf(e), chapterIndex, bookName)
                rename[e.name] = fn
                backLogs.add(desc)
            } else if (!hasHistory || !eligibleHistoryExists(e, snapshot)) {
                val rec = createRecord(recs, e, chapterIndex)
                rename[e.name] = rec.name
                newLogs.add("${rec.name}（${rec.roletype}）")
            } else {
                pending.add(e)
            }
        }
        if (hitLogs.isNotEmpty()) {
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第4阶段】快速命中：${joinCapped(hitLogs)}")
        }
        if (backLogs.isNotEmpty()) {
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第4阶段】回跳命中：${joinCapped(backLogs)}")
        }
        if (newLogs.isNotEmpty()) {
            AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第4阶段】新建：${joinCapped(newLogs)}")
        }

        pending.forEach { e ->
            val cands = snapshot.filter { it.gender == e.gender && ageDistance(it.age, e.age) <= 1 }
            if (cands.isEmpty()) {
                val rec = createRecord(recs, e, chapterIndex)
                rename[e.name] = rec.name
                return@forEach
            }
            val ctx = buildStage4Context(bookName, chapterIndex, segments, e, cands, rename)
            val descs = cands.joinToString("、") { c ->
                c.name + (if (c.age.isNotBlank()) "(${c.age})" else "") +
                    (if (c.aliases.isNotBlank()) {
                        "，别名：" + aliasTokensOf(c.aliases).joinToString("、")
                    } else {
                        ""
                    })
            }
            val roleName = rename[e.name] ?: finalNameOf(e)
            val verdict = callStage4AI(ctx, roleName, descs, cfg, s4Refs, chapterIndex)
            var target: CharacterRecord? = null
            if (verdict != null && verdict.first) {
                val mn = verdict.second.orEmpty()
                target = cands.firstOrNull { it.name == mn || mn in aliasTokensOf(it.aliases) }
            }
            if (target != null) {
                val (fn, desc) = applyMerge(target, e, finalNameOf(e), chapterIndex, bookName)
                rename[e.name] = fn
                AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第4阶段·长文本匹配】$desc")
            } else {
                val rec = createRecord(recs, e, chapterIndex)
                rename[e.name] = rec.name
                AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章·第4阶段·长文本匹配】${e.name} 未命中 → 新建 ${rec.name}")
            }
        }

        val out = segments.map { s ->
            if (s.roleType != SpeechRoleType.Narrator && rename.containsKey(s.characterName)) {
                s.copy(characterName = rename.getValue(s.characterName))
            } else {
                s
            }
        }
        return out to recs
    }

    private suspend fun applyMerge(
        hist: CharacterRecord,
        e: Calibrated,
        eFinalName: String,
        chapterIndex: Int,
        bookName: String,
    ): Pair<String, String> {
        touchAppearance(hist, chapterIndex)
        if (hist.roletype == "路人") {
            if (e.roleType != "路人") {
                // 升级：核心/特殊 接管主名；旧路人主名（含后缀完整名）转别名
                val oldName = hist.name
                val voiceKept = hist.voice
                val aliasList = aliasTokensOf(hist.aliases).toMutableList()
                if (oldName.isNotBlank() && oldName != e.name && oldName !in aliasList) aliasList.add(oldName)
                aliasTokensOf(e.alias).forEach { t ->
                    if (t.isNotBlank() && t != e.name && t !in aliasList) aliasList.add(t)
                }
                hist.name = e.name
                hist.aliases = aliasList.filter { it.isNotBlank() && it != e.name }.distinct().joinToString("|")
                hist.roletype = e.roleType
                replaceMarkersAcrossBook(bookName, oldName, e.name)
                val voiceNote = if (voiceKept.isNotBlank()) "，声线保留：$voiceKept" else ""
                return e.name to "$oldName → ${e.name}（升级${e.roleType}$voiceNote）"
            }
            return hist.name to "${e.name} → ${hist.name}"
        }
        // 并入核心/特殊：别名加法（路人侧用完整名【第N章】；后缀是章节消歧凭据，必须保留）
        val effName = if (e.roleType == "路人") eFinalName else e.name
        val aliasList = aliasTokensOf(hist.aliases).toMutableList()
        if (effName.isNotBlank() && effName != hist.name && effName !in aliasList) aliasList.add(effName)
        aliasTokensOf(e.alias).forEach { t ->
            if (t.isNotBlank() && t != hist.name && t !in aliasList) aliasList.add(t)
        }
        hist.aliases = aliasList.filter { it.isNotBlank() && it != hist.name }.distinct().joinToString("|")
        return hist.name to "$effName → ${hist.name}"
    }

    private fun createRecord(
        recs: MutableList<CharacterRecord>,
        e: Calibrated,
        chapterIndex: Int,
    ): CharacterRecord {
        val existing = findRecord(recs, e.name)
            ?: aliasTokensOf(e.alias).firstNotNullOfOrNull { findRecord(recs, it) }
        if (existing != null) {
            touchAppearance(existing, chapterIndex)
            val aliasList = aliasTokensOf(existing.aliases).toMutableList()
            aliasTokensOf(e.alias).forEach { t ->
                if (t.isNotBlank() && t != existing.name && t !in aliasList) aliasList.add(t)
            }
            if (e.name != existing.name && e.name !in aliasList) aliasList.add(e.name)
            existing.aliases = aliasList.filter { it.isNotBlank() && it != existing.name }.distinct().joinToString("|")
            return existing
        }
        val finalName = if (e.roleType == "路人" && !e.name.contains("【第")) {
            e.name + "【第${chapterIndex + 1}章】"
        } else {
            e.name
        }
        val rec = CharacterRecord(
            name = finalName,
            aliases = if (e.alias.isNotBlank() && e.alias != e.name) e.alias else "",
            roletype = e.roleType,
            gender = e.gender,
            age = e.age,
            voice = "",
            lastAppearanceChapter = chapterIndex,
            appearanceCount = 1,
            appearanceChapters = mutableListOf(chapterIndex),
        )
        recs.add(0, rec)
        return rec
    }

    private fun touchAppearance(rec: CharacterRecord, chapterIndex: Int) {
        rec.lastAppearanceChapter = chapterIndex
        rec.appearanceCount += 1
        if (!rec.appearanceChapters.contains(chapterIndex)) rec.appearanceChapters.add(chapterIndex)
    }

    private suspend fun replaceMarkersAcrossBook(bookName: String, oldName: String, newName: String) {
        if (oldName.isBlank() || oldName == newName) return
        runCatching {
            dataRepository.rewriteMarkersAll(bookName, oldName, newName, writeLog = true)
            dataRepository.renameMergeLogTokens(bookName, oldName, newName)
        }.onFailure { AppLog.putAnalysis("分析V3·改名回写失败: ${it.localizedMessage}", it) }
    }

    private fun findRecord(recs: List<CharacterRecord>, name: String): CharacterRecord? {
        val n = name.trim()
        if (n.isBlank()) return null
        return recs.firstOrNull { it.name.equals(n, true) || aliasTokensOf(it.aliases).any { a -> a.equals(n, true) } }
    }

    private fun histMatch(records: List<CharacterRecord>, e: Calibrated): CharacterRecord? {
        val eTokens = (listOf(e.name) + aliasTokensOf(e.alias)).filter { it.isNotBlank() }
        for (rec in records) {
            val rTokens = (listOf(rec.name) + aliasTokensOf(rec.aliases)).filter { it.isNotBlank() }
            if (eTokens.any { t -> rTokens.any { r -> r == t } }) return rec
        }
        return null
    }

    private fun eligibleHistoryExists(e: Calibrated, snapshot: List<CharacterRecord>): Boolean =
        snapshot.any { it.gender == e.gender && ageDistance(it.age, e.age) <= 1 }

    private suspend fun buildStage4Context(
        bookName: String,
        chapterIndex: Int,
        segments: List<ChapterSpeechSegment>,
        e: Calibrated,
        cands: List<CharacterRecord>,
        rename: Map<String, String>,
    ): String {
        var startCh = (chapterIndex - 5).coerceAtLeast(0)
        val firstMention = findFirstMentionChapter(bookName, e.name, chapterIndex - 1)
        if (firstMention in 0 until startCh) startCh = firstMention

        val sb = StringBuilder()
        for (c in startCh until chapterIndex) {
            val rendered = renderChapterFromRepo(bookName, c)
            if (rendered.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("[chapter:").append(c).append("]\n").append(rendered)
            }
        }
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append("[chapter:").append(chapterIndex).append("]\n")
        sb.append(renderFinalScript(segments, rename))

        var ctx = stripNarrTag(sb.toString())
        val keepSet = HashSet<String>()
        keepSet.add(e.name)
        keepSet.add(rename[e.name] ?: e.name)
        if (e.roleType == "路人") keepSet.add(e.name + "【第${chapterIndex + 1}章】")
        keepSet.addAll(aliasTokensOf(e.alias))
        cands.forEach { c ->
            keepSet.add(c.name)
            keepSet.addAll(aliasTokensOf(c.aliases))
        }
        ctx = ctx.lines().joinToString("\n") { line ->
            if (line.startsWith("[chapter:")) {
                line
            } else {
                val spk = lineSpeakerOf(line)
                when {
                    spk == null -> line
                    spk in keepSet -> line
                    else -> {
                        val close = line.indexOf('〗', 1)
                        if (close != -1) line.substring(close + 1) else line
                    }
                }
            }
        }
        return stripEmoTags(ctx)
    }

    private suspend fun renderChapterFromRepo(bookName: String, chapter: Int): String {
        val rows = runCatching { dataRepository.loadChapterScript(bookName, chapter) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return ""
        val sb = StringBuilder()
        rows.forEach { r ->
            if (r.speaker.isBlank()) {
                sb.append(r.text).append("\n")
            } else {
                sb.append("〖").append(r.speaker).append("〗").append(r.text).append("\n")
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private suspend fun findFirstMentionChapter(bookName: String, baseName: String, maxChapter: Int): Int {
        if (baseName.length < 2 || maxChapter < 0) return -1
        for (c in 0..maxChapter) {
            val rows = runCatching { dataRepository.loadChapterScript(bookName, c) }.getOrDefault(emptyList())
            if (rows.isEmpty()) continue
            for (r in rows) {
                if (r.speaker.isNotBlank()) {
                    if (r.speaker == baseName) return c
                } else if (r.text.contains(baseName)) {
                    return c
                }
            }
        }
        return -1
    }

    private fun renderFinalScript(segments: List<ChapterSpeechSegment>, rename: Map<String, String>): String {
        val sb = StringBuilder()
        segments.forEach { s ->
            if (s.roleType == SpeechRoleType.Narrator) {
                sb.append("〖旁白〗").append(s.text).append("\n")
            } else {
                val nm = rename[s.characterName] ?: s.characterName.ifBlank { "旁白" }
                sb.append("〖").append(nm).append("〗").append(s.text).append("\n")
            }
        }
        return sb.toString().trimEnd('\n')
    }

    /** 文件产物写入（幂等）：all_clean_text/chapter_cache/book_rev + 书架索引 */
    private suspend fun writeFileArtifacts(
        bookName: String,
        chapterIndex: Int,
        bookUrl: String,
        segments: List<ChapterSpeechSegment>,
    ): Boolean {
        if (bookName.isBlank()) return false
        return runCatching {
            dataRepository.ensureBookInList(bookName)
            dataRepository.saveChapterScript(bookName, chapterIndex, bookUrl, renderScriptForStore(segments))
        }.onFailure { AppLog.putAnalysis("【分析V3·第${chapterIndex + 1}章】剧本文件写入失败: ${it.localizedMessage}", it) }.getOrDefault(false)
    }

    /** 落盘用剧本渲染：〖旁白〗/〖主名〗 + [[emo:情绪]] 前缀（与脚本文件格式一致） */
    private fun renderScriptForStore(segments: List<ChapterSpeechSegment>): String {
        val sb = StringBuilder()
        segments.forEach { s ->
            if (s.roleType == SpeechRoleType.Narrator) {
                sb.append("〖旁白〗").append(s.text).append("\n")
            } else {
                val nm = s.characterName.ifBlank { "旁白" }
                sb.append("〖").append(nm).append("〗")
                if (s.emotion.isNotBlank()) sb.append("[[emo:").append(s.emotion).append("]]")
                sb.append(s.text).append("\n")
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private fun lineSpeakerOf(line: String): String? {
        if (!line.startsWith("〖")) return null
        val end = line.indexOf('〗', 1)
        return if (end == -1) null else line.substring(1, end)
    }

    private fun stripNarrTag(s: String): String = s.replace("〖旁白〗", "")

    private fun stripEmoTags(s: String): String =
        Regex("\\[\\[(?:emo|emotion)\\s*[:=][^\\[\\]]*\\]\\]").replace(s, "")

    private suspend fun callStage4AI(
        context: String,
        roleName: String,
        candidateDescs: String,
        cfg: AnalysisConfigStore.Config,
        refs: List<AiSpeechClient.ModelRef>,
        chapterIndex: Int,
    ): Pair<Boolean, String?>? {
        if (context.isBlank() || roleName.isBlank() || candidateDescs.isBlank()) return null
        if (refs.isEmpty()) return null
        val promptFactory = { failHint: String ->
            buildStage4Prompt(cfg.stage4Prompt, roleName, context, candidateDescs, failHint)
        }
        return ai.completeValidated(refs, "只输出 JSON。", promptFactory, cfg.maxOutputTokens, logTag = "第${chapterIndex + 1}章·第4阶段") { raw ->
            validateStage4(raw)
        }
    }

    private fun buildStage4Prompt(
        cfgText: String,
        roleName: String,
        context: String,
        descs: String,
        failHint: String,
    ): String = buildString {
        append((cfgText.ifBlank { DEFAULT_STAGE4_HEAD }).replace("%ROLE%", roleName))
        append("\n\n【长文本】\n").append(context)
        append("\n\n【历史角色列表】\n").append(descs)
        append("\n\n").append(DEFAULT_STAGE4_RULES)
        if (failHint.isNotBlank()) {
            append("\n【重要】你上一次的输出存在以下问题：").append(failHint).append("。请修正后重新输出完整JSON。\n")
        }
        append("\n【输出格式】只输出纯JSON：\n{\"is_same\": true/false, \"main_name\": \"历史角色主名或null\"}")
    }

    private fun validateStage4(raw: String): ValidateOutcome<Pair<Boolean, String?>> {
        val root = ai.extractJson(raw) ?: return ValidateOutcome(null, "返回不是JSON对象")
        val rawSame: Any? = root.opt("is_same")
        val isSame: Boolean? = when (rawSame) {
            is Boolean -> rawSame
            is String -> when (rawSame.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
        var mn = root.optString("main_name").trim()
        if (mn.equals("null", true) || mn.equals("none", true)) mn = ""
        val errs = ArrayList<String>()
        if (isSame == null) errs.add("is_same不是布尔值")
        if (isSame == true && mn.isBlank()) errs.add("is_same为true但main_name缺失")
        if (errs.isNotEmpty()) return ValidateOutcome(null, errs.joinToString("；"))
        return ValidateOutcome(isSame!! to mn.takeIf { it.isNotBlank() })
    }

    // ---------------- 声线分配（三池） ----------------

    /**
     * B10.5·Q1：修复「声线未设置」。
     * 候选=全部已选中池合并（同类型多池取并集）；无候选时复刻原脚本兜底链：
     *   特殊 → 核心·青年（男青年/女青年）→ 默认对话（duihuaA/B，共享不占锁）。
     * 全链落空才留空，并以日志给出原因（缺池/无候选）。
     */
    private suspend fun assignVoices(records: List<CharacterRecord>, chapterIndex: Int): List<CharacterRecord> {
        val groups = runCatching { dataRepository.loadActiveVoiceGroups() }.getOrDefault(emptyList())
        val tag = "第${chapterIndex + 1}章·声线分配"

        fun poolTags(roletype: String): List<String> =
            groups.filter { it.effectiveRoleType() == roletype && it.tags.isNotEmpty() }
                .flatMap { it.tags }.distinct()

        if (groups.isEmpty()) {
            val blank = records.count {
                it.voice.isBlank() && (it.roletype == "核心" || it.roletype == "路人" || it.roletype == "特殊")
            }
            if (blank > 0) {
                AppLog.putAnalysis("【分析V3·$tag】未选中任何声线库 → $blank 条角色未分配声线（去「引擎与音色 → 配置列表」勾选声线库）")
            }
            return records
        }

        val used = records.mapNotNull { it.voice.takeIf { v -> v.isNotBlank() } }.toMutableSet()

        fun pickRandom(candidates: List<String>): String {
            val fresh = candidates.filter { it !in used }
            val sel = if (fresh.isNotEmpty()) {
                fresh.random()
            } else {
                used.removeAll(candidates.toSet())
                candidates.random()
            }
            used.add(sel)
            return sel
        }

        var assigned = 0
        var fallbackCount = 0
        var specialDowngrade = 0
        val unassigned = ArrayList<String>()
        records.forEach { r ->
            if (r.voice.isNotBlank()) return@forEach
            if (r.roletype != "核心" && r.roletype != "路人" && r.roletype != "特殊") return@forEach
            val prefix = dataRepository.expectedVoicePrefix(r.roletype, r.gender, r.age)
            val direct = poolTags(r.roletype).filter { it.startsWith(prefix) }
            var sel: String? = null
            var missReason = ""
            if (direct.isNotEmpty()) {
                sel = pickRandom(direct)
            } else {
                missReason = if (poolTags(r.roletype).isEmpty()) "缺「${r.roletype}」声线池" else "「$prefix」无候选"
                if (r.roletype == "特殊") {
                    val youth = if (r.gender == "女") "女青年" else "男青年"
                    val core = poolTags("核心").filter { it.startsWith(youth) }
                    if (core.isNotEmpty()) {
                        sel = pickRandom(core)
                        specialDowngrade++
                    }
                }
                if (sel == null) {
                    val dh = if (r.gender == "女") "duihuaB" else "duihuaA"
                    val dhTags = poolTags("默认对话").filter { it.startsWith(dh) }
                    if (dhTags.isNotEmpty()) {
                        sel = dhTags.random()
                        fallbackCount++
                    }
                }
            }
            if (sel != null) {
                r.voice = sel
                assigned++
            } else {
                unassigned.add(if (missReason.isBlank()) r.name else "${r.name}（$missReason）")
            }
        }
        if (assigned > 0 || unassigned.isNotEmpty()) {
            val note = buildString {
                if (fallbackCount > 0) append("，默认对话兜底 $fallbackCount 条")
                if (specialDowngrade > 0) append("，特殊降级核心 $specialDowngrade 条")
                if (unassigned.isNotEmpty()) {
                    append("；未获声线 ${unassigned.size} 条：")
                    append(unassigned.take(3).joinToString("、"))
                    if (unassigned.size > 3) append(" 等")
                }
            }
            AppLog.putAnalysis("【分析V3·$tag】本次新分配 $assigned 条$note")
        }
        return records
    }

    /** 日志条数控制：最多列 10 项，其余折叠为「…等 N 项」（B10.5·Q2 降噪） */
    private fun joinCapped(items: List<String>, max: Int = 10): String =
        if (items.size <= max) {
            items.joinToString("；")
        } else {
            items.take(max).joinToString("；") + "；…等 ${items.size} 项"
        }

    // ---------------- 工具 ----------------

    private fun loadWordSet(fileName: String): Set<String> = runCatching {
        val f = File(dataRepository.dataDir(), fileName)
        if (!f.exists()) return@runCatching emptySet<String>()
        val arr = JSONArray(f.readText().removePrefix("\uFEFF"))
        (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    private fun isBareClassWord(w: String): Boolean {
        val t = w.trim()
        return t in BARE_WORDS || t in bareExtraSet
    }

    private fun isSpecialClassWord(w: String): Boolean {
        val t = w.trim()
        return t in SPECIAL_WORDS || t in specialExtraSet
    }

    private fun inferGenderFromAge(rawAge: String): String {
        val a = rawAge.trim()
        if (a.startsWith("女") || a.contains("少女")) return "女"
        if (a.startsWith("男") || a.contains("少年")) return "男"
        return "男"
    }

    private fun standardizeAge(rawAge: String, gender: String): String {
        var r = rawAge.trim()
        if (gender == "男" && (r.startsWith("女") || r.contains("少女"))) {
            r = r.replace("少女", "少年").replace("女", "男")
        }
        if (gender == "女" && (r.startsWith("男") || r.contains("少年"))) {
            r = r.replace("少年", "少女").replace("男", "女")
        }
        return when {
            r.contains("童") || r.contains("幼") || r.contains("婴") -> if (gender == "女") "女童" else "男童"
            r.contains("老") -> if (gender == "女") "女老年" else "男老年"
            r.contains("中") -> if (gender == "女") "女中年" else "男中年"
            r.contains("青") || r.contains("轻") -> if (gender == "女") "女青年" else "男青年"
            else -> if (gender == "女") "女青年" else "男青年"
        }
    }

    private fun ageLevel(age: String): Int = when {
        age == "系统" -> 0
        age.contains("童") -> 1
        age.contains("少年") || age.contains("少女") -> 2
        age.contains("青年") -> 3
        age.contains("中年") -> 4
        age.contains("老年") -> 5
        else -> 3
    }

    private fun ageDistance(a: String, b: String): Int {
        if (a == "系统" && b == "系统") return 0
        if (a == "系统" || b == "系统") return 99
        return abs(ageLevel(a) - ageLevel(b))
    }
}
