package com.customai.voice;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoiceAdapter {
  private static final String PREF="custom_ai_voice_session_v2";
  private static final long START_TIMEOUT_MS=3500L;
  private static final AtomicBoolean INSTALLED=new AtomicBoolean(false);
  private VoiceAdapter(){}

  public static synchronized boolean install(Context context) throws Exception {
    if (INSTALLED.get()) return true;
    Class<?> factory=Class.forName("com.ai.assistance.operit.api.voice.VoiceServiceFactory");
    Field f=factory.getDeclaredField("instance"); f.setAccessible(true);
    Object delegate=f.get(null);
    if(delegate==null){
      Method get=factory.getDeclaredMethod("getInstance",Context.class); get.setAccessible(true);
      delegate=get.invoke(null,context);
    }
    if(delegate==null) return false;
    if(Proxy.isProxyClass(delegate.getClass()) && Proxy.getInvocationHandler(delegate) instanceof Handler) {
      INSTALLED.set(true); return true;
    }
    Class<?> iface=Class.forName("com.ai.assistance.operit.api.voice.VoiceService");
    Object proxy=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},new Handler(context.getApplicationContext(),delegate));
    f.set(null,proxy); INSTALLED.set(true);
    prefs(context).edit().putBoolean("voice_installed",true).putString("voice_version","0.2.15").apply();
    return true;
  }

  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
  private static long now(){return android.os.SystemClock.elapsedRealtime();}
  private static boolean hasCyrillic(String s){return s!=null && s.matches(".*[\\u0400-\\u04FF].*");}
  private static void setField(Object o,String n,Object v){
    for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass()) try{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);return;}catch(Throwable ignored){}
  }
  private static boolean speaking(Object d){
    try{Method m=d.getClass().getMethod("isSpeaking"); return Boolean.TRUE.equals(m.invoke(d));}catch(Throwable t){return false;}
  }
  private static Object invoke(Method m,Object d,Object[] a)throws Throwable{
    try{return m.invoke(d,a);}catch(InvocationTargetException e){throw e.getCause();}
  }

  private static final class Handler implements InvocationHandler {
    final Context context; final Object delegate;
    Handler(Context c,Object d){context=c;delegate=d;}
    public Object invoke(Object p,Method m,Object[] a)throws Throwable{
      String n=m.getName();
      if(n.equals("toString")) return "CustomAI VoiceAdapter v0.2.15 -> "+delegate;
      if(!n.equals("speak")) return VoiceAdapter.invoke(m,delegate,a);
      String text=a!=null&&a.length>0?String.valueOf(a[0]):"";
      SharedPreferences sp=prefs(context);
      long seq=sp.getLong("tts_seq",0)+1, ts=now(); long epoch=sp.getLong("session_epoch",0); boolean activeSession=sp.getBoolean("session_active",false); String ss=sp.getString("session_state","IDLE"); boolean greeting=activeSession && ("SESSION_OPENING".equals(ss)||"GREETING_PENDING".equals(ss));
      String lang=hasCyrillic(text)?"ru-RU":sp.getString("active_language","es-US");
      if(lang==null||lang.isEmpty()) lang="es-US";
      if(lang.toLowerCase(Locale.ROOT).startsWith("ru")){
        setField(delegate,"currentLocaleTag","ru-RU");
        String ru=sp.getString("russian_voice_id","ru-ru-x-rue-local");
        setField(delegate,"currentVoiceId",ru);
      } else if(lang.toLowerCase(Locale.ROOT).startsWith("es")){
        setField(delegate,"currentLocaleTag","es-US");
        setField(delegate,"currentVoiceId",null);
      }
      sp.edit().putLong("tts_seq",seq).putString("tts_state","REQUESTED")
        .putLong("tts_requested_at_ms",ts).putString("tts_preview",text.substring(0,Math.min(80,text.length())))
        .putLong("tts_session_epoch",epoch).putString("tts_purpose",greeting?"GREETING":"ASSISTANT_RESPONSE")
        .putString("session_state",greeting?"GREETING_PENDING":ss).putString("semantic_state",greeting?"GREETING_PENDING":"TTS_REQUESTED").putString("audio_owner","TTS")
        .putString("last_reason",greeting?"GREETING_VOICE_REQUEST":"RESPONSE_VOICE_REQUEST").apply();
      Object result;
      try{result=VoiceAdapter.invoke(m,delegate,a);}
      catch(Throwable t){terminal(sp,seq,"FAILED","VOICE_DELEGATE_EXCEPTION");throw t;}
      if(Boolean.FALSE.equals(result)){terminal(sp,seq,"FAILED","VOICE_DELEGATE_FALSE");return result;}
      new Thread(()->monitor(sp,seq,epoch,greeting,delegate),"CustomAI-TTS-"+seq).start();
      return result;
    }
  }
  private static void terminal(SharedPreferences sp,long seq,String state,String reason){
    if(sp.getLong("tts_seq",-1)!=seq)return;
    sp.edit().putString("tts_state",state).putLong("tts_terminal_at_ms",now())
      .putString("semantic_state","FAILED").putString("audio_owner","NONE").putString("last_reason",reason).apply();
  }
  private static void monitor(SharedPreferences sp,long seq,long epoch,boolean greeting,Object delegate){
    long start=now(); boolean seen=false;
    while(now()-start<START_TIMEOUT_MS){
      if(sp.getLong("tts_seq",-1)!=seq)return; if(greeting && (!sp.getBoolean("session_active",false)||sp.getLong("session_epoch",-1)!=epoch))return;
      boolean s=speaking(delegate);
      if(s&&!seen){seen=true;sp.edit().putString("tts_state","SPEAKING").putLong("tts_started_at_ms",now())
        .putString("session_state",greeting?"GREETING_SPEAKING":sp.getString("session_state","IDLE")).putString("semantic_state",greeting?"GREETING_SPEAKING":"TTS_SPEAKING").putString("audio_owner","TTS").putString("last_reason","VOICE_SPEAKING_OBSERVED").apply();}
      if(seen&&!s){sp.edit().putString("tts_state","COMPLETED").putLong("tts_terminal_at_ms",now())
        .putString("session_state",greeting?"POST_TTS_GUARD":sp.getString("session_state","IDLE")).putString("semantic_state",greeting?"POST_TTS_GUARD":"TTS_COMPLETED").putString("audio_owner","NONE").putString("last_reason","VOICE_COMPLETED").apply();return;}
      try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
    }
    if(!seen) terminal(sp,seq,"START_TIMEOUT","VOICE_START_TIMEOUT");
  }
}
