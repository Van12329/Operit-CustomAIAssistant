package com.customai.speech;

import android.content.*;
import android.os.*;
import android.speech.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SpeechAdapter {
  private static final String PREF="custom_ai_voice_session_v2";
  private static final AtomicBoolean INSTALLED=new AtomicBoolean(false);
  private SpeechAdapter(){}
  public static synchronized boolean install(Context context)throws Exception{
    if(INSTALLED.get())return true;
    Class<?> factory=Class.forName("com.ai.assistance.operit.api.speech.SpeechServiceFactory");
    Field f=factory.getDeclaredField("instance");f.setAccessible(true);
    Object old=f.get(null);
    if(old==null){Method g=factory.getDeclaredMethod("getInstance",Context.class);g.setAccessible(true);old=g.invoke(null,context);}
    Class<?> iface=Class.forName("com.ai.assistance.operit.api.speech.SpeechService");
    Engine h=new Engine(context.getApplicationContext(),iface.getClassLoader());
    Object proxy=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},h);
    f.set(null,proxy);INSTALLED.set(true);
    prefs(context).edit().putBoolean("speech_installed",true).putString("speech_version","0.2.14").apply();
    return true;
  }
  static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
  static long now(){return SystemClock.elapsedRealtime();}

  static final class Engine implements InvocationHandler {
    final Context c; final Handler main=new Handler(Looper.getMainLooper());
    final Object initFlow,stateFlow,resultFlow,errorFlow,volumeFlow;
    volatile SpeechRecognizer sr; volatile boolean recognizing=false; volatile String active="es-US"; volatile long generation=0L; volatile long activeSessionEpoch=0L;
    final StringBuilder cumulative=new StringBuilder(); volatile long lastLexicalAt=0L; volatile long turnToken=0L;
    final Class<?> stateClass,resultClass,errorClass;
    Engine(Context c,ClassLoader cl)throws Exception{
      this.c=c;
      Class<?> sf=Class.forName("kotlinx.coroutines.flow.StateFlowKt");
      Method mf=sf.getMethod("MutableStateFlow",Object.class);
      stateClass=Class.forName("com.ai.assistance.operit.api.speech.SpeechService$RecognitionState");
      resultClass=Class.forName("com.ai.assistance.operit.api.speech.SpeechService$RecognitionResult");
      errorClass=Class.forName("com.ai.assistance.operit.api.speech.SpeechService$RecognitionError");
      initFlow=mf.invoke(null,Boolean.TRUE);
      stateFlow=mf.invoke(null,enumVal("IDLE"));
      resultFlow=mf.invoke(null,newResult("",false,0f));
      errorFlow=mf.invoke(null,newError(0,""));
      volumeFlow=mf.invoke(null,Float.valueOf(0f));
    }
    Object enumVal(String n){return Enum.valueOf((Class)stateClass,n);}
    Object newResult(String t,boolean fin,float conf)throws Exception{
      for(Constructor<?> k:resultClass.getDeclaredConstructors()){k.setAccessible(true);Class<?>[] p=k.getParameterTypes();if(p.length>=3)return k.newInstance(t,fin,conf);}
      throw new IllegalStateException("RecognitionResult constructor");
    }
    Object newError(int code,String msg)throws Exception{
      for(Constructor<?> k:errorClass.getDeclaredConstructors()){k.setAccessible(true);if(k.getParameterCount()==2)return k.newInstance(code,msg);}
      throw new IllegalStateException("RecognitionError constructor");
    }
    void set(Object flow,Object v){try{flow.getClass().getMethod("setValue",Object.class).invoke(flow,v);}catch(Throwable ignored){}}
    public Object invoke(Object proxy,Method m,Object[] a)throws Throwable{
      String n=m.getName();
      if(n.equals("toString"))return "CustomAI SpeechAdapter v0.2.14";
      if(n.equals("isInitialized")||n.equals("getIsInitialized"))return initFlow;
      if(n.equals("isRecognizing")||n.equals("getIsRecognizing"))return recognizing;
      if(n.equals("getCurrentState"))return recognizing?enumVal("RECOGNIZING"):enumVal("IDLE");
      if(n.equals("getRecognitionStateFlow"))return stateFlow;
      if(n.equals("getRecognitionResultFlow"))return resultFlow;
      if(n.equals("getRecognitionErrorFlow"))return errorFlow;
      if(n.equals("getVolumeLevelFlow"))return volumeFlow;
      if(n.equals("initialize"))return Boolean.TRUE;
      if(n.equals("startRecognition")){start(a);return Boolean.TRUE;}
      if(n.equals("stopRecognition")){main.post(()->stop(false));return Boolean.TRUE;}
      if(n.equals("cancelRecognition")){main.post(()->stop(true));return unit();}
      if(n.equals("shutdown")){main.post(()->{stop(true);if(sr!=null){sr.destroy();sr=null;}});return null;}
      if(n.equals("getSupportedLanguages"))return Arrays.asList("ru-RU","es-US");
      if(n.equals("recognize"))return unit();
      return null;
    }
    Object unit(){try{return Class.forName("kotlin.Unit").getField("INSTANCE").get(null);}catch(Throwable t){return null;}}
    void start(Object[] a){
      SharedPreferences sp=prefs(c); long req=sp.getLong("session_open_request_id",0)+1;
      sp.edit().putLong("speech_start_requests",sp.getLong("speech_start_requests",0)+1).putLong("session_open_request_id",req).putLong("session_open_requested_at_ms",now()).putString("last_reason","SPEECH_START_REQUEST").apply();
      long g=++generation; gate(g,0);
    }
    void gate(long g,int elapsed){
      if(g!=generation)return; SharedPreferences sp=prefs(c);
      if(!sp.getBoolean("session_active",false)){if(elapsed<4000){main.postDelayed(()->gate(g,elapsed+30),30);return;} failClosed("SESSION_NOT_OPENED");return;}
      long epoch=sp.getLong("session_epoch",0); activeSessionEpoch=epoch; String ts=sp.getString("tts_state","NONE"); long te=sp.getLong("tts_session_epoch",0);
      if(te==epoch&&("REQUESTED".equals(ts)||"SPEAKING".equals(ts))){sp.edit().putLong("speech_gate_deferrals",sp.getLong("speech_gate_deferrals",0)+1).apply();main.postDelayed(()->gate(g,elapsed+30),30);return;}
      if(te==epoch&&"COMPLETED".equals(ts)){long left=120-(now()-sp.getLong("tts_terminal_at_ms",0));if(left>0){main.postDelayed(()->gate(g,elapsed),left);return;}beginRecognizer(g,epoch);return;}
      if(te==epoch&&("FAILED".equals(ts)||"START_TIMEOUT".equals(ts))){failClosed("GREETING_"+ts);return;}
      if(elapsed<4000){main.postDelayed(()->gate(g,elapsed+30),30);return;}
      failClosed("GREETING_NOT_OBSERVED_CURRENT_SESSION");
    }
    void failClosed(String reason){prefs(c).edit().putString("session_state","FAILED").putString("semantic_state","FAILED").putString("audio_owner","NONE").putString("last_reason",reason).apply();}
    void beginRecognizer(long g,long epoch){ if(g!=generation)return; SharedPreferences sp=prefs(c); if(!sp.getBoolean("session_active",false)||sp.getLong("session_epoch",-1)!=epoch||"TTS".equals(sp.getString("audio_owner","NONE")))return;
      try{
        if(sr==null){sr=SpeechRecognizer.createSpeechRecognizer(c);sr.setRecognitionListener(listener());}
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,active);
        if(Build.VERSION.SDK_INT>=34){
          i.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION",true);
          i.putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH",true);
          i.putStringArrayListExtra("android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES",new ArrayList<>(Arrays.asList("ru-RU","es-US")));
          i.putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES",new ArrayList<>(Arrays.asList("ru-RU","es-US")));
        }
        recognizing=true;set(stateFlow,enumVal("RECOGNIZING"));
        prefs(c).edit().putString("semantic_state","LISTENING").putString("audio_owner","STT").putLong("android_start_listening_calls",prefs(c).getLong("android_start_listening_calls",0)+1).putString("last_reason","STT_STARTED").apply();
        sr.startListening(i);
      }catch(Throwable t){error(9001,String.valueOf(t));}
    }
    RecognitionListener listener(){
      return new RecognitionListener(){
        public void onReadyForSpeech(Bundle b){}
        public void onBeginningOfSpeech(){}
        public void onRmsChanged(float r){set(volumeFlow,Math.max(0f,Math.min(1f,(r+2f)/12f)));}
        public void onBufferReceived(byte[] b){}
        public void onEndOfSpeech(){set(stateFlow,enumVal("PROCESSING"));}
        public void onError(int e){recognizing=false;error(e,"SpeechRecognizer error "+e); if(e==SpeechRecognizer.ERROR_NO_MATCH||e==SpeechRecognizer.ERROR_SPEECH_TIMEOUT){long g=generation,epoch=activeSessionEpoch;main.postDelayed(()->beginRecognizer(g,epoch),180);}}
        public void onResults(Bundle b){String t=best(b);if(t!=null&&!t.trim().isEmpty()){if(cumulative.length()>0)cumulative.append(" ");cumulative.append(t.trim());lastLexicalAt=now();turnToken++;try{set(resultFlow,newResult(cumulative.toString(),false,confidence(b)));}catch(Throwable ignored){}}recognizing=false;long token=turnToken,g=generation,e=activeSessionEpoch;main.postDelayed(()->beginRecognizer(g,e),120);main.postDelayed(()->finalizeIfQuiet(token,g,e),1100);}
        public void onPartialResults(Bundle b){String t=best(b);if(t!=null&&!t.trim().isEmpty()){lastLexicalAt=now();String joined=cumulative.length()==0?t.trim():cumulative.toString()+" "+t.trim();try{set(resultFlow,newResult(joined,false,confidence(b)));}catch(Throwable ignored){}}}
        public void onEvent(int e,Bundle b){}
        public void onLanguageDetection(Bundle b){
          if(Build.VERSION.SDK_INT>=34&&b!=null){String l=b.getString("android.speech.extra.DETECTED_LANGUAGE");if(l!=null){if(l.toLowerCase(Locale.ROOT).startsWith("ru"))active="ru-RU";else if(l.toLowerCase(Locale.ROOT).startsWith("es"))active="es-US";prefs(c).edit().putString("active_language",active).apply();}}
        }
      };
    }
    String best(Bundle b){ArrayList<String> xs=b==null?null:b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);return xs==null||xs.isEmpty()?null:xs.get(0);}
    float confidence(Bundle b){float[] cs=b==null?null:b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);return cs!=null&&cs.length>0?cs[0]:0f;}
    void finalizeIfQuiet(long token,long g,long epoch){if(g!=generation||epoch!=activeSessionEpoch||token!=turnToken)return;long quiet=now()-lastLexicalAt;if(quiet<1100){main.postDelayed(()->finalizeIfQuiet(token,g,epoch),1100-quiet);return;}if(cumulative.length()==0)return;try{set(resultFlow,newResult(cumulative.toString(),true,1f));}catch(Throwable ignored){}cumulative.setLength(0);recognizing=false;set(stateFlow,enumVal("PROCESSING"));prefs(c).edit().putString("semantic_state","PROCESSING").putString("audio_owner","NONE").putString("last_reason","LEXICAL_CONTINUATION_FINAL").apply();}
    void emit(Bundle b,boolean fin){try{String t=best(b);if(t!=null)set(resultFlow,newResult(t,fin,confidence(b)));}catch(Throwable t){error(9002,String.valueOf(t));}}
    void error(int code,String msg){try{set(errorFlow,newError(code,msg));set(stateFlow,enumVal("ERROR"));prefs(c).edit().putString("last_speech_error",code+":"+msg).apply();}catch(Throwable ignored){}}
    void stop(boolean cancel){generation++;main.removeCallbacksAndMessages(null);try{if(sr!=null){if(cancel)sr.cancel();else sr.stopListening();if(cancel){sr.destroy();sr=null;}}}catch(Throwable ignored){}recognizing=false;activeSessionEpoch=0;set(stateFlow,enumVal("IDLE"));prefs(c).edit().putString("audio_owner","NONE").putString("last_reason",cancel?"STT_CANCELLED_GENERATION_INVALIDATED":"STT_STOPPED").apply();}
  }
}
