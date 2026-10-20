package io.legado.app.ui.ttssrv

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 千问克隆音色列表探针。
 *
 * 旧接口 `/api/v1/user/voice/list` 已废弃（返回 404 HTML）；
 * 现网接口是 qwen-live.qianwen.com 的 `/api/web/v1/user/voice/list`，
 * 必须带页面 `__QIANWEN_CHAT_SDK__.qwenSign()` 生成的签名头。
 * 本脚本直接复用页面里的签名 SDK，不逆签名。
 */
private const val QWEN_LIST_JS = """(function(){
  window.__qw='PENDING';
  (async function(){
    function hex16(){
      var a=new Uint8Array(16); crypto.getRandomValues(a); var s='';
      for(var i=0;i<a.length;i++){ s+=('0'+a[i].toString(16)).slice(-2); } return s;
    }
    async function waitSdk(){
      for(var i=0;i<50;i++){
        if(window.__QIANWEN_CHAT_SDK__ && typeof window.__QIANWEN_CHAT_SDK__.qwenSign==='function') return true;
        await new Promise(function(r){setTimeout(r,200);});
      }
      return false;
    }
    function norm(j){
      var vl=(j&&j.data&&j.data.voice_list)||[];
      var out=[];
      for(var i=0;i<vl.length;i++){
        var v=vl[i];
        if(v && (!v.status || v.status==='COMPLETED')) out.push({voice:v.voice_id,name:v.voice_name});
      }
      return out;
    }
    try{
      if(!await waitSdk()){ window.__qw='ERR:no_sdk'; return; }
      var sdk=window.__QIANWEN_CHAT_SDK__;
      var bases=['https://qwen-live.qianwen.com',location.origin,'https://coral3.qianwen.com'];
      var errs=[];
      for(var bi=0;bi<bases.length;bi++){
        for(var mi=0;mi<2;mi++){
          var method=mi===0?'POST':'GET';
          var all=[], pos=null, guard=0, ok=false, last='';
          while(guard++<20){
            try{
              var u=new URL('/api/web/v1/user/voice/list', bases[bi]);
              u.searchParams.set('size','20');
              u.searchParams.set('chid',hex16());
              if(pos!==null) u.searchParams.set('next_pos',String(pos));
              var signed=await sdk.qwenSign(u.toString());
              var headers=Object.assign({'content-type':'application/json','accept':'application/json'}, signed.signedHeader||signed.signedHeaders||{});
              var r=await fetch(signed.signedUrl||u.toString(),{method:method,headers:headers,credentials:'include',body:method==='POST'?'{}':undefined});
              var text=await r.text(); last='HTTP'+r.status+' '+text.slice(0,160);
              if(r.status!==200){ break; }
              var j; try{ j=JSON.parse(text); }catch(e){ break; }
              var arr=norm(j);
              if(arr.length){ all=all.concat(arr); ok=true; }
              else if(j && (j.code===0||j.code===2000000)){ ok=true; }
              else { last='code='+(j&&j.code)+' '+(j&&j.message)+' '+text.slice(0,120); break; }
              pos=(j&&j.metadata)?j.metadata.next_pos:null;
              if(pos===null||pos===undefined||pos===''||!arr.length) break;
            }catch(e){ last='EX:'+e; break; }
          }
          if(ok && all.length){ window.__qw=JSON.stringify(all); return; }
          errs.push(bases[bi]+' '+method+' '+last);
        }
      }
      window.__qw='ERR:'+errs.join(' | ').slice(0,600);
    }catch(e){ window.__qw='ERR:'+e; }
  })();
  return 'started';
})()"""

/** 启动探针并等待 window.__qw 出结果；返回数组 JSON 或 ERR:诊断。 */
internal suspend fun awaitQwenVoiceList(
    webView: WebView,
    timeoutMs: Long = 25_000,
): String {
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String> { cont ->
            webView.evaluateJavascript(QWEN_LIST_JS) { value ->
                if (cont.isActive) cont.resume(value ?: "")
            }
        }
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        delay(500)
        val raw = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { cont ->
                webView.evaluateJavascript("window.__qw||''") { value ->
                    if (cont.isActive) cont.resume(value ?: "")
                }
            }
        }.trim()
        if (raw.isEmpty() || raw == "null" || raw == "\"\"" || raw.contains("PENDING")) continue
        return decodeProbeString(raw)
    }
    return ""
}

/** evaluateJavascript 返回的是 JSON 字面量（带引号/转义）或 null。 */
private fun decodeProbeString(raw: String): String {
    if (raw == "null") return ""
    return runCatching {
        when (val value = JSONTokener(raw).nextValue()) {
            is String -> value
            else -> ""
        }
    }.getOrElse { raw.removeSurrounding("\"") }
}