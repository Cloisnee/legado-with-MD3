package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.ReadAloudCatalogRepository
import io.legado.app.data.repository.WordLibraryRepository
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.gateway.RaDirectionAudio
import io.legado.app.domain.gateway.RaDirectionGateway
import io.legado.app.domain.gateway.RaDirectionLine
import io.legado.app.domain.gateway.RaDirectionPlan
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAnalysisOrigin
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaNameUtils
import io.legado.app.domain.model.readaloud.v4.RaPipelineResult
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.utils.AliasTokens
import io.legado.app.help.readaloud.analysis.v4.SpeakerBinder
import io.legado.app.utils.MD5Utils
import io.legado.app.help.readaloud.audio.AudioLaneScan
import io.legado.app.help.readaloud.audio.AudioPlan
import io.legado.app.help.readaloud.audio.AudioPlanItem
import io.legado.app.help.readaloud.audio.AudioPlanStore
import io.legado.app.help.readaloud.audio.AudioPositions
import io.legado.app.help.readaloud.audio.AudioPrefill
import io.legado.app.help.readaloud.audio.AudioTagCodec
import io.legado.app.help.readaloud.audio.SynthLane
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

/**
 * 分析管线 V3 —— 复刻自研朗读脚本（重构 1.4.9.x）四阶段流程（第三阶段已按甲方要求去除）：
 *
 *  触发：调度器按“预加载窗口”驱动；连续性（上一章为最近完成解析章）决定第1阶段是否走 AI 判定。
 *  A 话语分析：本地规则 v2（新书首章 / 非连续章 / 队列空 / AI失败 → 快速路径）或 AI 判定（B31：逐项 T/F）
 *    （〖第N段〗+[n] 编号；校验=段号/全覆盖/长度全量收集→failHint 顺延重试；装配=逐项判定→叙述连T按句收、引号独立、零改写）。
 *  B 归属+人物：必须走 AI。入参=前情提要+本章 [n]〔〕/[N] 行+后续剧情（按完整段落取、上限可配、无人物表）；
 *    校验复刻（seq 全覆盖、characters 归一化、裸词/特殊词校准、默认男/男青年、路人清别名、本地归一）；
 *    失败不降级旁白：话语改由 默认对话(duihuaA/B) 发声。
 *  D 历史对比：快速命中（名/别名精确）→ 回跳命中 → 同性别±1年龄候选 + 长文本 AI 同判 → 合并（别名并、类型升级）
 *    或新建（路人=主名+【第N章】）。
 *  情绪：独立队列与第2阶段并发；第4阶段完成后最多再等 joinTimeout，超时先落库（无情绪剧本）。
 *  声线分配：三池（核心/路人/特殊）按 性别/年龄类键 未用优先随机 + 耗尽清锁重来再随机；只吃“已选中”声线库。
 *    无候选时复刻原脚本兜底链：特殊→核心·青年 → 默认对话(duihuaA/B)；全缺则留空并记日志（B10.5·Q1）。
 */
class SpeechAnalysisPipelineV4(
    private val bookIdentity: io.legado.app.domain.gateway.RaBookIdentityGateway,
    private val raBuild: io.legado.app.domain.gateway.RaBuildGateway,
    private val raCharacters: io.legado.app.domain.gateway.RaCharacterGateway,
    private val raDirection: io.legado.app.domain.gateway.RaDirectionGateway,
    private val catalog: io.legado.app.data.repository.ReadAloudCatalogRepository,
    private val resolver: io.legado.app.help.readaloud.analysis.v4.IdentityResolver,
    private val casting: io.legado.app.help.readaloud.analysis.v4.VoiceCastingService,
    private val wordLibrary: io.legado.app.data.repository.WordLibraryRepository,
    private val aiModels: AiModelRepository,
    private val ai: AiSpeechClient,
    private val configStore: AnalysisConfigStore,
) {

    companion object {
        const val RESOLVER_VERSION = "v4.1"

        /** 内置默认提示词（「AI 分析设置」页载入编辑器用）；key: stage1/stage2/stage4/emotion。
         *  stage4 含 %ROLE% 占位（角色名）、情绪含 %VOCAB% 占位（情绪词表），运行时自动替换。 */
        fun defaultPrompt(key: String): String = when (key) {
            "stage1" -> DEFAULT_STAGE1_PROMPT
            "stage2" -> DEFAULT_STAGE2_PROMPT
            "stage4" -> DEFAULT_STAGE4_HEAD
            "audioDirector" -> DEFAULT_AUDIO_DIRECTOR_PROMPT
            else -> DEFAULT_EMOTION_PROMPT
        }

        private val EMOTIONS = listOf("平静", "愉快", "悲伤", "愤怒", "恐惧", "惊讶", "厌恶", "严肃", "温柔", "鄙夷")

        private val DEFAULT_STAGE1_PROMPT = """
你是一名专业的小说文本话语标注员。输入文本已按片段编号：每段以〖第N段〗开始，段内片段为 [1][2]…（引号随原文保留）。你的任务：对每一个片段判定 T 或 F：
- T = 是话语（角色说出口或心里说出的具体内容，含语言性拟声如“啊——！”）；
- F = 不是话语（旁白叙述、心理概括、引导语、非语言性拟声、纯标点等）。

一、核心判断标准（先记住这一条）
话语 = 有意识主体用语言形式直接表达的具体内容，必须包含有语义的文字。
判断一个片段是否属于话语，唯一需要回答的问题是：这个片段里，有没有角色（或拟人化主体）正在“说话”或“心里说话”？如果答案是“有”，就标 T；如果只是叙述者在描述、解释、概括，或者只是声音、标点，就标 F。

二、话语包括哪些类型
1. 有声话语：角色说出口的话，包含对白、独白、喊叫、语言性拟声词（如“啊——！”“唉”）。但注意，动物叫声、物体声音不算。
2. 无声话语：
- 内心独白：角色在心里想但未说出口的具体话语，短促、口语化，符合角色性格，如“完了”“这地方不对劲”。这是网文难点，见第三条专门规则。
- 书面文字：书信、纸条、日记、微信、弹幕、邮件等以角色口吻写出的内容。

三、内心独白判定规则
内心独白是角色在心里“说”的话，不是叙述者对角色心理的概括。
判定方法：可发声测试
把疑似片段加上双引号，想象角色直接说出这句话：如果通顺、像人话、符合角色口吻，就是内心独白（T）；如果像是叙述者在解释状态、形容情绪、做总结，就不是（F）。
典型正例（是内心独白）：①完了，这地方不对劲。②该不会真有脏东西吧？③死渣男，还敢装无辜。④不对，这脚印是新的。
典型反例（不是内心独白，是心理描写或旁白）：①他感到害怕。②他心中一惊。③他陷入了绝望。④这让他很沮丧。⑤他的后背全是冷汗。
注意：内心独白可以没有“心想”“暗想”等引导词，直接融入叙述。不要因为一个短句没有引号就忽略它；也不要因为有“心想”就把后面所有内容都当话语，后面可能跟着叙述者的概括。

四、明确排除（必须标 F）
1. 叙述者的旁白、解释、评论、心理概括。例：他感到很害怕 → F。
2. 非语言性拟声词：动物叫声（汪汪、喵）、自然声（哗啦、轰隆）、物体声（砰、咔嚓）、机械声等。例：大狗咆哮：“汪汪汪！” → “汪汪汪！”标 F。
3. 纯标点片段，即使被引号包裹。例：张三：“……” → “……”标 F。
4. 引导语片段，如“他说：”“心想：”“上面写着：”等，本身不是话语，即使紧邻话语也标 F。

五、判定规则
1. 逐片段判定：每个片段必须给出一个值（T 或 F），一个都不能漏。
2. 各片段的值按编号顺序排列成数组；T/F 必须大写。
3. 全部段落都要输出，包括没有话语的段落（该段全 F）。
4. 不确定时：宁 F 勿 T——只有能通过“可发声测试”的片段才标 T。

六、输出格式
只输出纯 JSON，格式如下：{"段落":[{"段号":1,"判定":["F","F","F","F"]},{"段号":2,"判定":["T"]}]}
“判定”数组的长度必须等于该段的片段总数（按编号顺序一一对应）。
不要输出任何解释、注释或多余文字。

七、示例（务必对照学习）
示例1：对白+引导语排除
片段：[1]他愣了一下， [2]说道： [3]“你今天必须给我回去吃饭” [4]雨还在下。
输出：{"段落":[{"段号":1,"判定":["F","F","T","F"]}]}
示例2：内心独白无引导词，连续片段
片段：[1]他脚步一顿。 [2]不对， [3]这脚印是新的。 [4]他抬头看向前方。
输出：{"段落":[{"段号":1,"判定":["F","T","T","F"]}]}
示例3：心理描写，不是话语
片段：[1]他心里很害怕， [2]后背全是冷汗， [3]腿也软了。
输出：{"段落":[{"段号":1,"判定":["F","F","F"]}]}
示例4：内心独白+心理描写混合
片段：[1]完了， [2]这次死定了。 [3]他感到一阵绝望。
输出：{"段落":[{"段号":1,"判定":["T","T","F"]}]}
（[3]是心理描写，标 F）
示例5：非语言拟声排除
片段：[1]大狗咆哮： [2]“汪汪汪！”
输出：{"段落":[{"段号":1,"判定":["F","F"]}]}
示例6：纯标点排除
片段：[1]张三： [2]“……”
输出：{"段落":[{"段号":1,"判定":["F","F"]}]}
示例7：书面文字
片段：[1]他打开信， [2]上面写着： [3]“今晚八点，老地方见。”
输出：{"段落":[{"段号":1,"判定":["F","F","T"]}]}
示例8：语言性拟声词
片段：[1]她突然 [2]“啊——” [3]地叫了一声。
输出：{"段落":[{"段号":1,"判定":["F","T","F"]}]}
示例9：带“心想”但后面是概括
片段：[1]他心里想： [2]这次可能真的要失败。 [3]这让他很沮丧。
输出：{"段落":[{"段号":1,"判定":["F","T","F"]}]}
（[3]是叙述者评价，标 F）

八、最后一道检查（防幻觉）
在输出前，对每个片段做一次快速自检：
- 这个片段里的文字，是角色说出来的话或心里说出来的话吗？
- 如果把它用双引号括起来，像角色在说话吗？
- 它包含具体的字词句，而不是只有声音或标点吗？
三个问题都回答“是”，才标 T；否则标 F。
""".trimIndent()

        private val DEFAULT_STAGE2_PROMPT = """
你是一个小说角色分析专家。下面的小说文本中，[1][2] 等为话语编号，紧随其后的〔〕内是该话语的内容（外层引号已去除）；[N] 开头的行为旁白叙述。

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
你是一个小说对话情绪分析专家。下面的文本中，[1][2] 等为话语编号，紧随其后的〔〕内是该话语的内容（外层引号已去除）；[N] 开头的行为旁白叙述。
任务：对每条编号话语，判断说话人发出这句话时的【真实情绪/发声状态】。注意：字面情绪可能与真实情绪相反（反话、冷嘲、压抑、强装镇定、笑里藏刀等），有反差线索时优先判定隐藏情绪；无线索按正常表达判定。
情绪词必须严格从以下词表选择：%VOCAB%。拿不准时选“平静”。
输出：纯JSON，格式：{"emotions":{"1":"愤怒","2":"悲伤"}}，键为话语编号、值为情绪词，必须覆盖文本中的每一个编号，禁止输出任何其他文字。
""".trimIndent()

        /**
         * B33.4b · 音频导演默认提示词：
         *  - 拟音师口径：所有会响的动静都要标（有动静就标；连续过程合并一条）；
         *  - 命名=简短、具体、常见（对接素材库检索与合成平台）；
         *  - BGM 结构化四段关键词（题材/场景/情绪/速度）+hold；红线：禁跳吓、不确定不坐实。
         */
        private val DEFAULT_AUDIO_DIRECTOR_PROMPT = """
你是一名资深有声书配音导演、拟音导演与声音轨道规划器，你的唯一任务：阅读完章节文本后，以有声剧声音设计师/混音师的判断力规划 ambience、bgm、sfx 三类声音事件。

【角色判断力｜融合自声音设计师工作方式】
你输出每一条判断前，内部先过一遍声音设计师的工作内容，而不是套模板：
- 这个场景的声音世界是什么：地点、时间、天气、空间大小、混响感。
- 人物在做什么动作，动作的材质、距离、听感是什么。
- 此刻情绪曲线走到哪，BGM 该不该进，进了会不会抢对白。
- 前后场怎么衔接，环境或 BGM 是否延续。

【输出契约】
只输出JSON，不要输出 markdown、解释或多余文字，示例如下：
<输入文本>
〖第1段〗
[1]晴天客栈，[2]人声低杂，[3]杯盏轻碰，[4]桌椅拖响，[5]空阔回旋。
〖第2段〗
[1]他推门而入，[2]老木门干涩一响，[3]发出轻微吱呀声。
〖第3段〗
[1]她起身，[2]似是不敢相信他的决定。[3]他只能道声保重，[4]便转身离去。
<输出JSON>
{"items":[{"para":2,"frag":1,"anchor":"他推门而入","type":"sfx","tag":"推门声","desc":"为推门声生成拟音，老木门材质，干涩摩擦带轻微吱呀，声音形态为带自然衰减尾音，时长4秒，为纯净立体声干音，无背景杂音、无多余底噪，峰值音量控制在-8db。","pos":"前"},{"para":1,"type":"ambience","tag":"客栈大堂","desc":"为客栈大堂白天晴天生成20秒环境声，包含低缓人声嘈杂底噪+杯盏轻碰特征声+远处桌椅拖动声，室内中大型空间感，无对白无音乐，可循环，立体声，整体音量基准控制在-30dB至-18dB区间。"},{"para":3,"type":"bgm","theme":"古风","scene":"离别","mood":"悲情","speed":"慢速","hold":12,"desc":"为古代离别场景生成45秒配乐，悲情，二胡与低音弦乐，60BPM，D小调，动态起伏克制，有主题，输出频段均衡听感舒适，无刺耳高频，总峰值统一控制在-10dB。"}]}

每条 item 字段：
- para 为段落数〖n〗，必填，整数
- frag 为片段数[m]，sfx 必填；ambience/bgm 可省，缺省=该段首片
- anchor 可选，原文片段，便于校对
- type 必填，只能是 ambience / sfx / bgm
- tag sfx/ambience 必填，2~6字具体短名
- pos sfx 必填，前/中/后，前=片段前1/3≈17％处，中=片段中部≈50％处，后=后1/3≈83％处
- delayMs sfx 可选，若你认为sfx pos仍不够准确时可用之做延迟，但不能超过限定范围（0~2000 毫秒）
- desc 必填，合成声效的具体描述
- theme / scene / mood / speed / hold 为bgm必填项目

【音效】
1. tag＝音效名称。
2. 每个 para 最多 3 条 sfx。
3. 同一 frag 最多 1 条 sfx。
4. 软件有 3 秒最小间隔，超限会被丢弃。
5. 优先级：剧情动作 > 器物互动 > 自然/环境小动 > 主观声。低优先级不输出。
6. 连续过程只标一次；似乎/仿佛/隐约不标；安静处不标。
7.desc 模板：为[tag]生成拟音，[材质]，[听感]，声音形态为[短促干净无余音/带自然衰减尾音/厚重浑浊带共鸣]，时长3-5秒，为纯净立体声干音，无背景杂音、无多余底噪，峰值音量控制在-8db。

【环境 ambience】
1. 只在场景变化处输出，一章约 1~5 条。
2. tag=场景名，地点+天气/时段。
3. desc 模板：为[地点][时间][天气]生成[时长15-30秒]环境声，包含[底噪]+[特征声]+[远处声]，[空间感]，无对白无音乐，可循环，立体声，整体音量基准控制在[最低dB]至[最高dB]区间。

【BGM】
1. 只在关键剧情/情绪处起乐，一章 0~2 条。
2. hold=从本段起持续行数，8~40。
3. 四段词必须来自值集；**优先选用下列高频词**（素材库覆盖率最高，选得越常见越容易命中现成配乐；素材库没有的组合会自动转平台合成，应尽量避免）：
   题材高频：通用/历史/都市/恐怖/科幻/悬疑/古风/现代
   场景高频：日常/战斗/室内/古代/夜晚/雨夜/街头/城市/梦境/客栈
   情绪高频：平静/紧张/悲伤/压抑/英勇/温暖/悬疑/恐怖/诡异/悲情
   速度：无把握时用中速（库内占比最高）
4. desc 模板：为[主题][场景]生成[30-60秒]配乐，[情绪]，[乐器]，[BPM]，[调性]，[动态]，[无强旋律/有主题]，输出频段均衡听感舒适，无刺耳高频，总峰值统一控制在-10dB。

【值集】
theme：通用/历史/都市/恐怖/科幻/悬疑/古风/现代/浪漫/幻想/武侠/仙侠/民国/未来/奇幻
scene：战斗/日常/追逐/调查/过场/转场/古代/梦境/城市/太空/夜晚/雨夜/森林/市集/客栈/山门/古寺/战场/宫殿/室内/回忆/弄堂/邮局/酒店/酒吧/咖啡馆/派对/婚礼/约会/地铁/街头/街道/地下城/夜城/基地/公寓/客厅/书房/阳台/码头/山路/旅途/探险/寻宝/废墟/垃圾场/宫斗/权谋/对峙/会战/表白/离别/重逢/刑侦/探案/推理/案情/赛车/竞速/飞行/赛博朋克/月夜/竹林/庭院/山水/丝绸之路/钟楼/教堂/医院/学校/教室/酒馆/茶馆/当铺/渔村/山村/小镇/霓虹/人群/信号/照片/电话/磁带/暗影/鬼魅/诅咒/禁地/心魔/门扉/床底/天台/安全屋/荒野/葬礼/尸体/壁画/冲击/利刃/地下/审判
mood：平静/舒缓/温馨/悲情/凄凉/紧张/压迫感/热血/史诗/幽默/轻快/悲伤/英勇/压抑/温暖/空灵/诡异/神秘/黑暗/罗曼蒂克/危险/绝望/喜剧/俏皮/深沉/肃穆/激烈/不祥/孤独/悲痛/沉重/静谧/震撼/恢弘/抒情/清新/温柔/文雅/伤感/惆怅/轻松/轻缓/孤寂/安静/诙谐/搞笑/怀旧/感人/空旷/惊悚/庄严/和缓/浪漫/恐怖/悬疑
speed：慢速/中速/快速

【命名】
tag 只用 2~6 字常见具体词。
音效如「推门声」「茶杯碎裂」「剑鸣」「马蹄声」；
环境如「客栈大堂」「雨夜街道」「山间清晨」。
禁止抽象修辞、引号、方括号、长句。
同类场景同类词，稳定优先。

【本地建议审核】
合理则原样采用；不准确改短名；多余删除；遗漏按硬约束补。
BGM 建议缺段从值集补全。

【红线】
禁止跳吓：恐怖抽弦、突然巨响、惊悚重音不用。
武器/暴力克制，不猎奇。
""".trimIndent()
    }

    // ---------------- 数据小结构 ----------------

    private val pipelineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> AppLog.putAnalysis("V4·后台任务异常: ${e.localizedMessage}", e) },
    )

    private data class TextUnit(val start: Int, val end: Int, val text: String)
    private data class ParaUnits(val paraIndex: Int, val units: List<TextUnit>)
    private data class SpRange(val para: Int, val start: Int, val end: Int)
    private data class Stage2Payload(
        val seqMap: Map<Int, String>,
        val chars: List<Calibrated>,
        val narratorSeqs: Set<Int> = emptySet(),
    )

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

    @Volatile
    private var bareWordsCache: Set<String> = emptySet()

    @Volatile
    private var specialWordsCache: Set<String> = emptySet()

    /** B19：当前 run 的章节显示名（标题截到章；run 由调度器串行调用，用实例字段免逐层透传） */
    @Volatile
    private var chapterLabel: String = ""


    // ---------------- 主流程 ----------------

    suspend fun run(
        bookUrl: String,
        bookName: String,
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        prevChapterText: String,
        nextChapterText: String,
        force: Boolean = false,
    ): RaPipelineResult? = withContext(Dispatchers.IO) {
        if (paragraphs.isEmpty()) return@withContext null
        val contentHash = SpeechIdentity.chapterContentHash(paragraphs)
        chapterLabel = runCatching { catalog.chapterLabel(bookUrl, chapterIndex) }
            .getOrDefault("第${chapterIndex + 1}章")
        val existing = runCatching {
            raBuild.getAnalysis(bookKey, sourceKey, chapterIndex, contentHash, RESOLVER_VERSION)
        }.getOrNull()
        if (!force && existing != null && existing.status == RaAnalysisStatus.RESOLVED) {
            val cached = runCatching { raBuild.segments(existing.id) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                return@withContext RaPipelineResult(existing, cached, true)
            }
        }

        val cfg = configStore.load()
        bareWordsCache = runCatching { wordLibrary.bareWords() }.getOrDefault(emptySet())
        specialWordsCache = runCatching { wordLibrary.specialWords() }.getOrDefault(emptySet())
        val analysisId = SpeechIdentity.analysisId(
            bookKey = bookKey,
            sourceKey = sourceKey,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            resolverVersion = RESOLVER_VERSION,
        )
        AppLog.putAnalysis("【V4·$chapterLabel·阶段1】开始：段数=${paragraphs.size}")

        // ===== 阶段1：话语选号（D4：所有章节统一走 AI；失败回退本地引号规则） =====
        val t0 = System.currentTimeMillis()
        val ranges = stageA(paragraphs, cfg, chapterIndex = chapterIndex)
        val directorRefs = runCatching { aiModels.queueRefs("audioDirector") }.getOrDefault(emptyList())
        val directorSkipReason = when {
            directorRefs.isEmpty() -> "未设置Ai"
            else -> ""
        }
        if (ranges.isEmpty()) {
            AppLog.putAnalysis("【V4·$chapterLabel·阶段1】未检出话语（${System.currentTimeMillis() - t0}ms）→ 全旁白")
        }
        var segments = assemble(paragraphs, ranges)
        AppLog.putAnalysis(
            "【V4·$chapterLabel·阶段1】完成 ${System.currentTimeMillis() - t0}ms：话语 ${segments.count { it.roleType != SpeechRoleType.Narrator }}",
        )

        // 音频导演（暂时保持现状；结果双写 Room 计划表，播放仍读旧存储到 V4-4）
        val unitsByPara = buildParaUnits(paragraphs)
        val flatUnits = unitsByPara.flatMap { pu -> pu.units.map { it.text } }
        val unitSuggestions: List<List<AudioLaneScan.Suggestion>> = runCatching {
            AudioLaneScan.suggest(flatUnits)
        }.getOrNull() ?: List(flatUnits.size) { emptyList() }
        if (directorSkipReason.isNotEmpty()) {
            logLaneFallbackSuggestions(directorSkipReason, unitSuggestions)
        }

        // ===== 阶段2：对白归属（AI） =====
        val dialogueSegs = segments.filter { it.roleType != SpeechRoleType.Narrator }
        val numbered = renderNumbered(segments)
        val unitCounts: Map<Int, Int> = unitsByPara.associate { it.paraIndex + 1 to it.units.size }
        val directorJob = if (directorSkipReason.isEmpty() && unitsByPara.isNotEmpty()) {
            val directorText = renderDirectorUnits(unitsByPara, unitSuggestions)
            pipelineScope.async {
                callAudioDirector(directorText, unitCounts, cfg, directorRefs, chapterLabel)
            }
        } else null
        val s2Refs = runCatching { aiModels.queueRefs("stage2") }.getOrDefault(emptyList())
        val emoRefs = runCatching { aiModels.queueRefs("emotion") }.getOrDefault(emptyList())
        val emoSeqIndex = dialogueSegs.mapIndexed { i, s -> (s.paragraphIndex to s.start) to i + 1 }.toMap()
        val emoJob = if (emoRefs.isNotEmpty() && dialogueSegs.isNotEmpty()) {
            pipelineScope.async { callEmotion(numbered, dialogueSegs.size, cfg, emoRefs, chapterIndex, chapterLabel) }
        } else null
        var usedAi2 = false
        var entries: List<Calibrated> = emptyList()
        var seqMap: Map<Int, String> = emptyMap()
        if (s2Refs.isNotEmpty() && dialogueSegs.isNotEmpty()) {
            val s2 = callStage2(numbered, prevChapterText, nextChapterText, cfg, dialogueSegs.size, s2Refs, chapterIndex)
            if (s2 != null) {
                usedAi2 = true
                entries = s2.chars
                seqMap = s2.seqMap
                segments = applyStage2(segments, s2.seqMap, s2.narratorSeqs)
                AppLog.putAnalysis(
                    "【V4·$chapterLabel·阶段2】完成：人物 ${entries.size} 个（seq ${s2.seqMap.size}/${dialogueSegs.size}）",
                )
            } else {
                AppLog.putAnalysis("【V4·$chapterLabel·阶段2】失败：话语改用默认对话(duihuaA/duihuaB)发声")
            }
        } else if (dialogueSegs.isNotEmpty()) {
            AppLog.putAnalysis("【V4·$chapterLabel·阶段2】跳过：未配模型队列 → 话语改用默认对话发声")
        }

        // ===== 身份收敛（V4-1 确定性最小版） =====
        val localSpeakers = buildLocalSpeakers(analysisId, entries, chapterIndex)
        val keyToCharacter = HashMap<String, String>()
        val keyToResolution = HashMap<String, RaResolution>()
        val resolvedCharacters = ArrayList<io.legado.app.domain.model.readaloud.v4.RaCharacter>()
        localSpeakers.forEach { speaker ->
            val resolution = resolver.resolve(bookKey, chapterIndex, speaker)
            keyToCharacter[speaker.localKey] = resolution.characterId
            keyToResolution[speaker.localKey] = resolution.resolution
            raCharacters.character(resolution.characterId)?.let(resolvedCharacters::add)
        }
        segments = SpeakerBinder.bind(segments, localSpeakers, keyToCharacter, seqMap)
        // 用户台词级覆盖：重析后按 (段号, 文本哈希) 重新套用
        val overrides = runCatching { raBuild.overrides(bookKey, chapterIndex) }
            .getOrDefault(emptyList())
            .associateBy { it.paragraphIndex to it.textHash }
        if (overrides.isNotEmpty()) {
            segments = segments.map { segment ->
                val override = overrides[segment.paragraphIndex to MD5Utils.md5Encode16(segment.text)]
                    ?: return@map segment
                if (override.narrator) {
                    segment.copy(
                        roleType = SpeechRoleType.Narrator,
                        characterId = null,
                        localSpeakerId = null,
                        surfaceName = "",
                        seq = 0,
                    )
                } else {
                    segment.copy(characterId = override.characterId ?: segment.characterId)
                }
            }
        }
        val updatedSpeakers = localSpeakers.map { speaker ->
            speaker.copy(
                characterId = keyToCharacter[speaker.localKey],
                resolution = keyToResolution[speaker.localKey] ?: RaResolution.PENDING,
            )
        }
        if (localSpeakers.isNotEmpty()) {
            AppLog.putAnalysis(
                "【V4·$chapterLabel·收敛】完成：人物 ${localSpeakers.size}（链接 ${keyToResolution.count { it.value == RaResolution.RESOLVED }}、临时 ${keyToResolution.count { it.value != RaResolution.RESOLVED }}）",
            )
        }
        casting.ensureDefaultProfiles(bookKey, resolvedCharacters.distinctBy { it.id }, chapterLabel)

        // ===== 情绪（V4-1 仍用旧提示词；结果写 ra_direction_line） =====
        val emotionBySeq = HashMap<Int, String>()
        if (emoJob != null) {
            val emo = withTimeoutOrNull(cfg.emotionJoinTimeoutMs) { emoJob.await() }
            if (emo != null && emo.isNotEmpty()) {
                emotionBySeq.putAll(emo)
                AppLog.putAnalysis("【V4·$chapterLabel·情绪】覆盖 ${emo.size}/${dialogueSegs.size} 条")
            } else if (emo == null && emoJob.isActive) {
                AppLog.putAnalysis("【V4·$chapterLabel·情绪】${cfg.emotionJoinTimeoutMs}ms 内未返回，先落库")
            }
        }

        // ===== 音频导演 join（暂时保留时域兜底） =====
        var audioPlan: AudioPlan? = null
        if (directorJob != null) {
            val items = withTimeoutOrNull(cfg.audioDirectorJoinTimeoutMs) { directorJob.await() }
            when {
                items != null -> {
                    val plan = convertDirectorItems(items, unitsByPara, segments)
                        .copy(scriptHash = contentHash)
                    audioPlan = plan
                    AppLog.putAnalysis(
                        "【V4·$chapterLabel·音效与背景音】Ai导演：${plan.countsText()}。",
                    )
                    runCatching { AudioPlanStore.save(bookName, bookUrl, chapterIndex, plan) }
                }
                directorJob.isActive -> logLaneFallback("Ai超时", paragraphs)
                else -> logLaneFallback("Ai失败", paragraphs)
            }
        } else {
            val rulesPlan = AudioPrefill.buildRulesPlan(
                buildFallbackSeeds(unitsByPara, unitSuggestions, segments),
            )
            if (rulesPlan.itemCount > 0) {
                audioPlan = rulesPlan.copy(scriptHash = contentHash)
                AppLog.putAnalysis(
                    "【V4·$chapterLabel·音效与背景音】本地计划已写入：${rulesPlan.countsText()}。",
                )
                runCatching { AudioPlanStore.save(bookName, bookUrl, chapterIndex, audioPlan) }
            }
        }

        // ===== 落库（一个事务：analysis + segments + localSpeakers + evidence + build） =====
        val stage2Failed = dialogueSegs.isNotEmpty() && !usedAi2
        val now = System.currentTimeMillis()
        val analysis = RaAnalysis(
            id = analysisId,
            bookKey = bookKey,
            sourceKey = sourceKey,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            resolverVersion = RESOLVER_VERSION,
            status = if (stage2Failed) RaAnalysisStatus.FAILED else RaAnalysisStatus.RESOLVED,
            origin = if (force) RaAnalysisOrigin.USER_REANALYZE else RaAnalysisOrigin.FOREGROUND,
            modelId = "",
            createdAt = now,
            updatedAt = now,
        )
        val bound = segments.map { segment ->
            segment.copy(
                id = SpeechIdentity.segmentId(analysisId, segment.paragraphIndex, segment.start, segment.end),
                analysisId = analysisId,
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                createdAt = now,
                updatedAt = now,
            )
        }
        val olderBuild = raBuild.getBuild(bookKey, sourceKey, chapterIndex)
        val build = RaChapterBuild(
            bookKey = bookKey,
            sourceKey = sourceKey,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            state = if (stage2Failed) RaChapterState.FAILED else RaChapterState.IDENTITY_READY,
            analysisId = analysisId,
            failReason = if (stage2Failed) "阶段2未完成：未配置模型或调用失败" else "",
            attempts = (olderBuild?.attempts ?: 0) + if (stage2Failed) 1 else 0,
            createdAt = olderBuild?.createdAt ?: now,
            updatedAt = now,
        )
        raBuild.saveAnalysisBundle(
            analysis = analysis,
            segments = bound,
            localSpeakers = updatedSpeakers,
            evidence = emptyList(),
            build = build,
        )
        AppLog.putAnalysis(
            "【V4·$chapterLabel·落库】完成：段 ${bound.size}、人物 ${updatedSpeakers.size}、AI=$usedAi2" +
                (if (stage2Failed) "（阶段2失败，标记 FAILED 待重试）" else ""),
        )
        resolvedCharacters.distinctBy { it.id }.forEach { character ->
            runCatching { raCharacters.recomputeAppearance(character.id) }
        }

        // ===== 导演行（情绪）与音频条目双写 Room =====
        saveDirectionRows(analysisId, bookKey, chapterIndex, bound, emotionBySeq, audioPlan, now)
        RaPipelineResult(analysis, bound, false)
    }


    /** 本地快速分段（点击朗读 → 先出声）：引号规则 v2 → 段落装配；不落库。 */
    fun quickLocalSegments(paragraphs: List<CanonicalSpeechParagraph>): List<RaSegment> {
        val ranges = ArrayList<SpRange>()
        paragraphs.forEach { p ->
            QuoteSpeechRules.quoteSpans(p.text).forEach { s ->
                ranges.add(SpRange(p.index, s.first, s.last + 1))
            }
        }
        val pruned = pruneSymbolOnly(paragraphs, ranges)
        if (pruned > 0) {
            AppLog.putAnalysis("【V4·快速链】纯符号话语过滤（转旁白）：${pruned} 段")
        }
        return assemble(paragraphs, ranges)
    }

    private suspend fun buildLocalSpeakers(
        analysisId: String,
        entries: List<Calibrated>,
        chapterIndex: Int,
    ): List<RaLocalSpeaker> = entries.mapIndexed { index, entry ->
        val names = (listOf(entry.name) + AliasTokens.of(entry.alias))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        RaLocalSpeaker(
            id = "$analysisId#L${index + 1}",
            analysisId = analysisId,
            localKey = "L${index + 1}",
            surfaceNames = names,
            primaryName = entry.name,
            roleType = entry.roleType,
            gender = entry.gender,
            ageBand = entry.age,
            resolution = RaResolution.PENDING,
        )
    }

    private suspend fun saveDirectionRows(
        analysisId: String,
        bookKey: String,
        chapterIndex: Int,
        segments: List<RaSegment>,
        emotionBySeq: Map<Int, String>,
        audioPlan: AudioPlan?,
        now: Long,
    ) {
        val planId = "dp_${analysisId}_PLAYBACK_BASE"
        val lines = ArrayList<RaDirectionLine>()
        segments.forEach { segment ->
            if (segment.seq <= 0) return@forEach
            val emotion = emotionBySeq[segment.seq] ?: return@forEach
            lines.add(RaDirectionLine(segmentId = segment.id, emotionTag = emotion))
        }
        val audio = ArrayList<RaDirectionAudio>()
        if (audioPlan != null) {
            var idx = 0
            (audioPlan.ambience + audioPlan.bgm + audioPlan.sfx).forEach { item ->
                audio.add(
                    RaDirectionAudio(
                        idx = idx++,
                        type = item.type,
                        para = item.para,
                        frag = 0,
                        posRatio = item.posRatio,
                        tag = item.tag,
                        desc = item.desc,
                        delayMs = item.delayMs.toInt(),
                        hold = item.hold != 0,
                        theme = item.theme,
                        scene = item.scene,
                        mood = item.mood,
                        speed = item.speed,
                        anchor = item.anchor,
                    ),
                )
            }
        }
        if (lines.isEmpty() && audio.isEmpty()) return
        raDirection.savePlan(
            plan = RaDirectionPlan(
                id = planId,
                analysisId = analysisId,
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                module = "PLAYBACK_BASE",
                status = "READY",
                directorRevision = 1,
                identityRevisionSnapshot = 0,
                origin = "FOREGROUND",
            ),
            lines = lines,
            audio = audio,
        )
    }

    private suspend fun stageA(
        paragraphs: List<CanonicalSpeechParagraph>,
        cfg: AnalysisConfigStore.Config,
        chapterIndex: Int,
    ): List<SpRange> {
        // 本地路径：引号包裹规则 v2
        val local = ArrayList<SpRange>()
        paragraphs.forEach { p ->
            QuoteSpeechRules.quoteSpans(p.text).forEach { s ->
                local.add(SpRange(p.index, s.first, s.last + 1))
            }
        }
        // B28：纯符号"话语"（无汉字/数字/字母）转旁白（静默，不触发重试）
        val localPruned = pruneSymbolOnly(paragraphs, local)
        if (localPruned > 0) {
            AppLog.putAnalysis("【V4·${chapterLabel}·第1阶段】纯符号话语过滤（转旁白）：${localPruned} 段")
        }
        // AI 路径：逐项判定 T/F（B31；失败回退本地）
        val refs = runCatching { aiModels.queueRefs("stage1") }.getOrDefault(emptyList())
        if (refs.isEmpty()) {
            AppLog.putAnalysis("【V4·${chapterLabel}·第1阶段】未配模型队列 → 本地规则：${local.size} 段话语")
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
        val sel = ai.completeValidated(refs, "只输出 JSON。", promptFactory, cfg.maxOutputTokens, logTag = "${chapterLabel}·第1阶段") { raw ->
            validateSelection(raw, unitCounts)
        }
        if (sel != null) {
            val paras = cands.map { c ->
                SpeechSelectionAssembly.ParaUnits(
                    c.paraIndex,
                    c.units.map { u -> SpeechSelectionAssembly.Unit(u.start, u.end, u.text) },
                )
            }
            val asm = SpeechSelectionAssembly.assemble(paras, sel)
            val fromAi = asm.spans.map { SpRange(it.para, it.start, it.end) }
            AppLog.putAnalysis(
                "【V4·${chapterLabel}·第1阶段】AI判定：话语 ${fromAi.size} 条" +
                    "（旁白 ${asm.narratorUnits} 片；保险丝修正 ${asm.fusedColon + asm.fusedSymbol} 条）"
            )
            return fromAi
        }
        AppLog.putAnalysis("【V4·${chapterLabel}·第1阶段】AI判定失败 → 回退本地规则：${local.size} 段话语")
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

    /** 判定结构校验（B31.1：段号/长度/缺段 全量收集 → 带理由 failHint 重试；宽容解析 串/数组/布尔） */
    private fun validateSelection(
        raw: String,
        unitCounts: Map<Int, Int>,
    ): ValidateOutcome<Map<Int, BooleanArray>> {
        val root = ai.extractJson(raw) ?: return ValidateOutcome(null, "返回不是JSON对象")
        val list = root.optJSONArray("段落") ?: root.optJSONArray("段") ?: root.optJSONArray("paragraphs")
            ?: return ValidateOutcome(null, "缺少段落列表字段")
        val totalParas = unitCounts.size
        val map = HashMap<Int, BooleanArray>()
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
            val rawFlag: Any? = when {
                item.has("判定") -> item.opt("判定")
                item.has("flags") -> item.opt("flags")
                item.has("标记") -> item.opt("标记")
                else -> null
            }
            val flags = SpeechSelectionAssembly.parseFlags(jsonToPlainFlags(rawFlag), maxU)
            if (flags == null) {
                errs.add("第${n}段判定缺失或长度不符（本段共${maxU}片）")
                continue
            }
            map[n - 1] = flags
        }
        // B31.1：缺段=校验失败——带理由打回重析（不再宽容转旁白）
        val missing = unitCounts.filter { (para, cnt) -> cnt > 0 && para !in map }.keys.sorted()
        if (missing.isNotEmpty()) {
            val shown = missing.take(8).joinToString("、") { (it + 1).toString() }
            val tail = if (missing.size > 8) "…等${missing.size}个" else ""
            errs.add("缺少判定的段落：$shown$tail（每段都必须输出与片段数等长的判定数组）")
        }
        if (errs.isNotEmpty()) return ValidateOutcome(null, errs.joinToString("；"))
        return ValidateOutcome(map)
    }

    /** JSONArray → 纯 Kotlin 结构（供 [SpeechSelectionAssembly.parseFlags] 宽容解析） */
    private fun jsonToPlainFlags(v: Any?): Any? = when (v) {
        is JSONArray -> (0 until v.length()).map { v.opt(it) }
        else -> v
    }

    // ---------------- 装配 ----------------

    /** B28：第1阶段话语校验——仅由标点/符号构成（无任何汉字/数字/字母）的"话语"不成立 → 转旁白（原地剔除，静默不重试） */
    private fun pruneSymbolOnly(
        paragraphs: List<CanonicalSpeechParagraph>,
        ranges: MutableList<SpRange>,
    ): Int {
        val byIdx = paragraphs.associateBy { it.index }
        val before = ranges.size
        ranges.removeAll { r ->
            val pr = byIdx[r.para] ?: return@removeAll true
            val s = pr.text
            val st = r.start.coerceIn(0, s.length)
            val en = r.end.coerceIn(st, s.length)
            !(st < en && s.substring(st, en).any { it.isLetterOrDigit() })
        }
        return before - ranges.size
    }

    private fun assemble(
        paragraphs: List<CanonicalSpeechParagraph>,
        ranges: List<SpRange>,
    ): List<RaSegment> {
        val out = ArrayList<RaSegment>()
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
                    // B31：心理活动标签（Thought）注入已拔除（云链路待 B32 一并处置；枚举保留兼容老数据）
                    out.add(seg(p, rs2, re2, t, SpeechRoleType.Character))
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
    ): RaSegment = RaSegment(
        id = "",
        analysisId = "",
        bookKey = "",
        chapterIndex = 0,
        paragraphIndex = p.index,
        start = start,
        end = end,
        chapterPosition = p.chapterPosition + start,
        text = text,
        roleType = role,
        surfaceName = "",
        source = SpeechResolutionSource.Rule,
    )

    /** 编号渲染（B31：[n]〔content（外层引号已去除）〕/ [N]旁白；一行一项、段间空行）——发给第2阶段/情绪 */
    private fun renderNumbered(segments: List<RaSegment>): String {
        val sb = StringBuilder()
        var n = 0
        var lastP = -1
        segments.forEach { s ->
            if (lastP != -1 && s.paragraphIndex != lastP) sb.append("\n")
            if (s.roleType == SpeechRoleType.Narrator) {
                sb.append("[N]").append(s.text)
            } else {
                n++
                sb.append("[").append(n).append("]〔").append(QuoteSpeechRules.unwrapOuterBlock(s.text)).append("〕")
            }
            sb.append("\n")
            lastP = s.paragraphIndex
        }
        return sb.toString().trimEnd('\n')
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
        return ai.completeValidated(refs, "只输出 JSON。", promptFactory, cfg.maxOutputTokens, logTag = "${chapterLabel}·第2阶段") { raw ->
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
        // B30：全旁白退让——characters 为空但 seqmap 全部指向「旁白」时不再判失败（整体转旁白）
        if (arr.isEmpty()) {
            val allNarrator = sm.isNotEmpty() && sm.values.all { NarratorRoleStrip.isNarratorName(it) }
            if (!allNarrator) return ValidateOutcome(null, "characters为空")
        }

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
                    AppLog.putAnalysis("【V4·${chapterLabel}·第2阶段】seqmap校正：$v → $tgt")
                    sm[n] = tgt
                } else if (!NarratorRoleStrip.isNarratorName(v)) {
                    errs.add("seqmap中人物「$v」未在characters中定义")
                }
            }
        }
        // B30：旁白角色剥离——「旁白」不参与定义/引用校验、校准与归一（静默转真旁白，不判失败）
        byName.entries.removeAll { NarratorRoleStrip.isNarratorName(it.key) }
        val narratorSeqs = NarratorRoleStrip.narratorSeqs(sm)
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
        return ValidateOutcome(Stage2Payload(sm.toMap(), normalized, narratorSeqs))
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
                    AppLog.putAnalysis("【V4·${chapterLabel}·第2阶段】校准：主名「${c.name}」为裸属词，别名「${c.alias}」上位为主名")
                    c.name = c.alias
                    c.alias = ""
                } else {
                    AppLog.putAnalysis("【V4·${chapterLabel}·第2阶段】校准：主名「${c.name}」与别名均为裸属词，降级为路人")
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
                    AppLog.putAnalysis("【V4·${chapterLabel}·第2阶段】本地归一：${loser.name} 并入 ${winner.name}")
                    val toks = AliasTokens.of(winner.alias).toMutableList()
                    if (loser.name != winner.name && loser.name !in toks) toks.add(loser.name)
                    AliasTokens.of(loser.alias).forEach { t ->
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
        if (b.name in AliasTokens.of(a.alias)) return true
        if (a.name in AliasTokens.of(b.alias)) return true
        return false
    }

    private fun applyStage2(
        segments: List<RaSegment>,
        seqMap: Map<Int, String>,
        narratorSeqs: Set<Int> = emptySet(),
    ): List<RaSegment> {
        var n = 0
        return segments.map { s ->
            if (s.roleType == SpeechRoleType.Narrator) {
                s
            } else {
                n++
                when {
                    // B30：旁白角色剥离——剥夺角色属性（名字/归属），就地转真旁白（旁白声线合成）
                    n in narratorSeqs -> s.copy(
                        roleType = SpeechRoleType.Narrator,
                        surfaceName = "",
                        characterId = null,
                        source = SpeechResolutionSource.Ai,
                        seq = n,
                    )
                    seqMap[n].isNullOrBlank() -> s.copy(source = SpeechResolutionSource.Ai, seq = n)
                    else -> s.copy(
                        surfaceName = seqMap.getValue(n),
                        characterId = null,
                        source = SpeechResolutionSource.Ai,
                        confidence = 0.9f,
                        seq = n,
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
        label: String,
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
        return ai.completeValidated(refs, "只输出 JSON。", promptFactory, cfg.maxOutputTokens, logTag = "$label·情绪") { raw ->
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

    // ---------------- D 历史对比 + 记录库 ----------------

    // ---------------- B33.4b 音频导演 ----------------

    /** Ai 失败/超时兜底行：本地规则层快扫（播放侧后续回退同源扫描，口径一致） */
    private suspend fun logLaneFallback(reason: String, paragraphs: List<CanonicalSpeechParagraph>) {
        runCatching {
            val laneSummary = AudioLaneScan.summaryText(paragraphs.map { it.text })
            if (laneSummary != null) {
                AppLog.putAnalysis(
                    "【V4·${chapterLabel}·音效与背景音】本地规则快速识别（$reason）：$laneSummary。"
                )
            }
        }
    }

    /** P1.5.1/P1.6.2：本地建议预扫行（跳过 Ai 导演时）；计数=唯一条目（与「本地计划」/音频侧「剧本统计」同口径，bgm/环境声/音效三件齐全） */
    private fun logLaneFallbackSuggestions(
        reason: String,
        suggestions: List<List<AudioLaneScan.Suggestion>>,
    ) {
        val uniq = suggestions.flatten().distinctBy { it.lane to it.label }
        val bgm = uniq.count { it.lane == SynthLane.BGM }
        val amb = uniq.count { it.lane == SynthLane.AMB }
        val sfx = uniq.count { it.lane == SynthLane.SFX }
        AppLog.putAnalysis(
            "【V4·${chapterLabel}·音效与背景音】本地建议预扫（$reason）：bgm${bgm}条、环境声${amb}条、音效${sfx}条。"
        )
    }

    /** B34.2b：导演输入=第1阶段片段文本（〖第N段〗+[m] 同款）+ 本地建议标记 */
    private fun renderDirectorUnits(
        unitsByPara: List<ParaUnits>,
        unitSuggestions: List<List<AudioLaneScan.Suggestion>>,
    ): String {
        val sb = StringBuilder()
        var fi = 0
        unitsByPara.forEach { c ->
            sb.append("〖第").append(c.paraIndex + 1).append("段〗\n")
            c.units.forEachIndexed { i, u ->
                sb.append("[").append(i + 1).append("] ").append(u.text)
                val marks = AudioPrefill.markersFor(unitSuggestions.getOrElse(fi) { emptyList() })
                if (marks.isNotEmpty()) sb.append(marks)
                sb.append("\n")
                fi++
            }
        }
        return sb.toString().trimEnd('\n')
    }

    /** B34.2c：导演条目（段/片段+片内pos）→ 章节计划（剧本行锚点=segOrdinal + 句内比例） */
    private fun convertDirectorItems(
        items: List<AudioDirectorContract.DirectorItem>,
        unitsByPara: List<ParaUnits>,
        segments: List<RaSegment>,
    ): AudioPlan {
        val amb = ArrayList<AudioPlanItem>()
        val bgm = ArrayList<AudioPlanItem>()
        val sfx = ArrayList<AudioPlanItem>()
        items.forEach { di ->
            val pu = unitsByPara.firstOrNull { p -> p.paraIndex == di.para - 1 } ?: return@forEach
            val u = pu.units.getOrNull((di.frag - 1).coerceAtLeast(0)) ?: return@forEach
            val seg = unitSegment(segments, pu.paraIndex, u.start) ?: return@forEach
            val ordinal = segments.indexOf(seg) + 1
            when (di.type) {
                AudioTagCodec.TYPE_AMB -> amb += AudioPlanItem(
                    ordinal, di.type, tag = di.tag, desc = di.desc, anchor = di.anchor,
                )

                AudioTagCodec.TYPE_BGM -> bgm += AudioPlanItem(
                    ordinal, di.type, tag = di.tag, desc = di.desc, hold = di.hold,
                    theme = di.theme, scene = di.scene, mood = di.mood, speed = di.speed, anchor = di.anchor,
                )

                else -> sfx += AudioPlanItem(
                    para = ordinal, type = di.type, tag = di.tag, desc = di.desc,
                    delayMs = di.delayMs,
                    posRatio = AudioPositions.ratioOfUnit(
                        seg.start, seg.end, u.start, u.text.length, di.posRatio,
                    ),
                    anchor = di.anchor,
                )
            }
        }
        return AudioPlan(
            source = "ai",
            ambience = amb.sortedBy { it.para },
            bgm = bgm.sortedBy { it.para },
            sfx = sfx.sortedBy { it.para },
        )
    }

    /** B34.2b：兜底计划种子——片段建议（含片内命中比）→ 剧本行锚点+句内比例 */
    private fun buildFallbackSeeds(
        unitsByPara: List<ParaUnits>,
        unitSuggestions: List<List<AudioLaneScan.Suggestion>>,
        segments: List<RaSegment>,
    ): List<AudioPrefill.PlanSeed> {
        val out = ArrayList<AudioPrefill.PlanSeed>()
        var fi = 0
        unitsByPara.forEach { pu ->
            pu.units.forEachIndexed { _, u ->
                val sugs = unitSuggestions.getOrElse(fi) { emptyList() }
                fi++
                if (sugs.isEmpty()) return@forEachIndexed
                val seg = unitSegment(segments, pu.paraIndex, u.start) ?: return@forEachIndexed
                val ordinal = segments.indexOf(seg) + 1
                sugs.forEach { sug ->
                    if (sug.label.isBlank()) return@forEach
                    out += AudioPrefill.PlanSeed(
                        segPara = ordinal,
                        lane = sug.lane,
                        label = sug.label,
                        ratio = AudioPositions.ratioOfUnit(
                            seg.start, seg.end, u.start, u.text.length, sug.ratio,
                        ),
                    )
                }
            }
        }
        return out
    }

    /** 片段起点所在剧本行（同段内包含 unitStart 的段；兜底取起点不超过它的最后一段） */
    private fun unitSegment(
        segments: List<RaSegment>,
        paraIndex: Int,
        unitStart: Int,
    ): RaSegment? {
        val segs = segments.filter { it.paragraphIndex == paraIndex }.sortedBy { it.start }
        if (segs.isEmpty()) return null
        return segs.lastOrNull { it.start <= unitStart && unitStart < it.end }
            ?: segs.firstOrNull { it.start <= unitStart }
            ?: segs.first()
    }

    /** 音频导演调用（队列两级重试；校验见 [AudioDirectorContract]） */
    private suspend fun callAudioDirector(
        numbered: String,
        unitCounts: Map<Int, Int>,
        cfg: AnalysisConfigStore.Config,
        refs: List<AiSpeechClient.ModelRef>,
        label: String,
    ): List<AudioDirectorContract.DirectorItem>? {
        if (refs.isEmpty() || numbered.isBlank()) return null
        val head = cfg.audioDirectorPrompt.ifBlank { DEFAULT_AUDIO_DIRECTOR_PROMPT }
        val promptFactory = { failHint: String ->
            buildString {
                append(head)
                if (failHint.isNotBlank()) {
                    append("\n【重要】你上一次的输出存在以下问题：").append(failHint)
                        .append("。请修正后重新输出完整JSON。\n")
                }
                append("\n=== 待规划文本 ===\n").append(numbered)
            }
        }
        return ai.completeValidated(refs, "只输出 JSON。", promptFactory, cfg.maxOutputTokens, logTag = "$label·音频导演") { raw ->
            ai.extractJson(raw)?.let { AudioDirectorContract.validate(it, unitCounts) }
                ?: ValidateOutcome(null, "返回不是JSON对象")
        }
    }

    /** 读取本章音频计划（scriptHash 与当前内容一致才采用；脚本写入用） */

    /** 日志条数控制：最多列 10 项，其余折叠为「…等 N 项」（B10.5·Q2 降噪） */
    private fun joinCapped(items: List<String>, max: Int = 10): String =
        if (items.size <= max) {
            items.joinToString("；")
        } else {
            items.take(max).joinToString("；") + "；…等 ${items.size} 项"
        }

    // ---------------- 工具 ----------------

    private fun isBareClassWord(w: String): Boolean {
        val t = w.trim()
        return t.isNotEmpty() && (t.length <= 1 || t in bareWordsCache)
    }

    private fun isSpecialClassWord(w: String): Boolean = w.trim() in specialWordsCache

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
}
