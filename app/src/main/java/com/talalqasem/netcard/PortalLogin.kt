package com.talalqasem.netcard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import kotlin.concurrent.thread

/**
 * تسجيل الدخول التلقائي على صفحة الشبكة (Captive Portal):
 * 1) يربط التطبيق بشبكة الواي فاي  2) يكتشف صفحة الدخول
 * 3) يملأ الخانتين (الأولى اليوزر والثانية كلمة السر) ويضغط دخول
 * 4) يتحقق من وصول الإنترنت
 */
class PortalLogin(
    private val ctx: Context,
    private val web: WebView,
    private val ui: Callback
) {
    interface Callback {
        fun onStatus(msg: String)
        fun onSuccess()
        fun onAlreadyOnline()
        fun onFailure(msg: String, showPage: Boolean)
    }

    private data class Probe(val online: Boolean, val url: String?)

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())

    private var user = ""
    private var pass = ""
    private var wifi: Network? = null
    private var submitted = false
    private var filling = false
    private var fillAttempts = 0
    private var submitTime = 0L
    private var token = 0
    private var logoutMode = false

    var isRunning = false
        private set

    init {
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (!isRunning) return
                val t = token
                if (logoutMode) {
                    handler.postDelayed({ if (t == token && logoutMode) clickFirstButton() }, 700)
                } else if (!submitted) {
                    handler.postDelayed({ if (t == token && !submitted) fill() }, 800)
                }
            }

            // صفحات الشبكات المفتوحة غالباً بشهادة غير موثوقة
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed()
            }
        }
    }

    private fun findWifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun gatewayOf(n: Network): String? =
        cm.getLinkProperties(n)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress

    private fun probe(n: Network): Probe {
        val base = "http://connectivitycheck.gstatic.com/generate_204"
        return try {
            val c = n.openConnection(URL(base)) as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.useCaches = false
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            c.disconnect()
            when {
                code == 204 -> Probe(true, null)
                loc != null -> Probe(false, URL(URL(base), loc).toString())
                else -> Probe(false, base)
            }
        } catch (e: Exception) {
            Probe(false, null)
        }
    }

    fun start(u: String, p: String) {
        if (isRunning) return
        isRunning = true
        logoutMode = false
        submitted = false
        filling = false
        fillAttempts = 0
        user = u
        pass = p
        val t = ++token

        val w = findWifi()
        if (w == null) {
            finishFail("لا توجد شبكة واي فاي متصلة. اتصل بالشبكة أولاً", false)
            return
        }
        wifi = w
        cm.bindProcessToNetwork(w)
        ui.onStatus("جارٍ فحص الشبكة...")

        handler.postDelayed({
            if (t == token && isRunning) finishFail("انتهت المهلة دون نجاح الدخول", true)
        }, 60000)

        thread {
            val r = probe(w)
            handler.post {
                if (t != token || !isRunning) return@post
                if (r.online) {
                    isRunning = false
                    token++
                    cm.bindProcessToNetwork(null)
                    ui.onAlreadyOnline()
                } else {
                    ui.onStatus("جارٍ فتح صفحة الدخول...")
                    web.loadUrl(r.url ?: "http://connectivitycheck.gstatic.com/generate_204")
                }
            }
        }
    }

    private fun fill() {
        if (filling) return
        filling = true
        val t = token
        web.evaluateJavascript(buildJs()) { res ->
            filling = false
            if (t != token || !isRunning) return@evaluateJavascript
            if ((res ?: "").contains("submitted")) {
                submitted = true
                submitTime = SystemClock.elapsedRealtime()
                ui.onStatus("تم إدخال الرقمين... جارٍ التحقق من الاتصال")
                poll(t)
            } else {
                fillAttempts++
                if (fillAttempts >= 4) {
                    finishFail("لم أجد خانات الدخول في صفحة الشبكة", true)
                } else {
                    handler.postDelayed({ if (t == token && !submitted) fill() }, 1500)
                }
            }
        }
    }

    private fun poll(t: Int) {
        handler.postDelayed({
            if (t != token || !isRunning) return@postDelayed
            val w = wifi
            thread {
                val online = w != null && probe(w).online
                handler.post {
                    if (t != token || !isRunning) return@post
                    when {
                        online -> finishOk()
                        SystemClock.elapsedRealtime() - submitTime > 25000 ->
                            finishFail("لم ينجح الدخول. تأكد أن البطاقة صالحة", true)
                        else -> poll(t)
                    }
                }
            }
        }, 1500)
    }

    private fun finishOk() {
        isRunning = false
        token++
        cm.bindProcessToNetwork(null)
        ui.onSuccess()
    }

    private fun finishFail(msg: String, showPage: Boolean) {
        isRunning = false
        token++
        cm.bindProcessToNetwork(null)
        ui.onFailure(msg, showPage)
    }

    fun cancel() {
        token++
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    // ---------- تسجيل الخروج ----------
    fun logout() {
        if (isRunning) {
            ui.onStatus("انتظر انتهاء العملية الحالية")
            return
        }
        val w = findWifi()
        if (w == null) {
            ui.onFailure("لا توجد شبكة واي فاي متصلة", false)
            return
        }
        val gw = gatewayOf(w)
        if (gw == null) {
            ui.onFailure("تعذّر تحديد بوابة الشبكة", false)
            return
        }
        wifi = w
        cm.bindProcessToNetwork(w)
        isRunning = true
        logoutMode = true
        val t = ++token
        ui.onStatus("جارٍ تسجيل الخروج من الشبكة...")
        web.loadUrl("http://$gw/logout")
        handler.postDelayed({
            if (t == token && isRunning) {
                isRunning = false
                logoutMode = false
                cm.bindProcessToNetwork(null)
                ui.onStatus("تم إرسال طلب تسجيل الخروج")
            }
        }, 6000)
    }

    private fun clickFirstButton() {
        logoutMode = false
        web.evaluateJavascript(
            "(function(){var b=document.querySelector('input[type=submit],button[type=submit],button,input[type=button]');" +
                "if(b){b.click();return 'clicked';}var f=document.forms[0];if(f){f.submit();return 'form';}return 'none';})()",
            null
        )
    }

    // ---------- سكربت تعبئة الخانات والضغط على دخول ----------
    private fun buildJs(): String {
        val u = JSONObject.quote(user)
        val p = JSONObject.quote(pass)
        return """
(function(u,p){
 try{
  var all=[].slice.call(document.querySelectorAll('input'));
  var ok=['text','tel','number','email','password','search',''];
  var f=all.filter(function(i){
    var t=(i.getAttribute('type')||'text').toLowerCase();
    return ok.indexOf(t)>=0 && !i.disabled && !i.readOnly;
  });
  var vis=f.filter(function(i){return i.offsetWidth>0||i.offsetHeight>0;});
  if(vis.length){f=vis;}
  if(!f.length){return 'nofields';}
  function setv(el,v){
    try{
      var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
      d.set.call(el,v);
    }catch(e){el.value=v;}
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
  }
  var pw=null;
  for(var i=0;i<f.length;i++){
    if((f[i].getAttribute('type')||'').toLowerCase()==='password'){pw=f[i];break;}
  }
  var us=null;
  if(pw){
    var k=f.indexOf(pw);
    us=k>0?f[k-1]:null;
  }else{
    us=f[0];
    pw=f.length>1?f[1]:null;
  }
  if(us){setv(us,u);}
  if(pw){setv(pw,p);}
  var form=(pw||us).form;
  var scope=form||document;
  var cands=scope.querySelectorAll('input[type=submit],button[type=submit],button,input[type=button],a.btn,a.button');
  var btn=null;
  for(var j=0;j<cands.length;j++){
    if(cands[j].offsetWidth>0||cands[j].offsetHeight>0){btn=cands[j];break;}
  }
  if(!btn&&cands.length){btn=cands[0];}
  if(!btn&&!form){return 'nosubmit';}
  setTimeout(function(){
    if(btn){btn.click();}
    else if(form.requestSubmit){form.requestSubmit();}
    else{form.submit();}
  },300);
  return 'submitted';
 }catch(e){return 'error:'+e;}
})($u,$p);
""".trimIndent()
    }
}
