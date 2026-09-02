package com.sprich.qa.editor;

import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.*;
import android.system.*;
import java.io.*;
import java.nio.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** External QA runner: loads the installed target APK's JNI, never its own native libraries.
 * This proves packaged native/R8 behavior, not microphone or editor behavior. */
public final class NativeRuntimeCheck extends Instrumentation {
    Bundle args;
    ClassLoader targetLoader;
    String prefix="com.k2fsa.sherpa.onnx.";
    public void onCreate(Bundle arguments) { super.onCreate(arguments); args=arguments==null?new Bundle():arguments; start(); }
    public void onStart() {
        Bundle output=new Bundle();
        try {
            boolean debuggable=(getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0;
            if(debuggable && !"true".equals(args.getString("allowDebug")))throw new AssertionError("Target must be the non-debuggable release APK");
            long pageSize=Os.sysconf(OsConstants._SC_PAGESIZE);
            String expectedPage=args.getString("expectedPageSize",args.getString("pageSize"));
            if(expectedPage!=null && pageSize!=Long.parseLong(expectedPage))throw new AssertionError("Unexpected kernel page size: "+pageSize);
            targetLoader=getTargetContext().getClassLoader();
            float[] pcm=readWav("jfk.wav");
            int runs=Integer.parseInt(args.getString("runs","3"));
            if(runs<1||runs>100)throw new AssertionError("Invalid run count");
            String modes=args.getString("modes","lid,fast");
            JSONArray results=new JSONArray();
            for(String mode:modes.split(",")) {
                if(!Arrays.asList("vad","lid","fast","canary").contains(mode))throw new AssertionError("Unknown mode");
                long loadStart=SystemClock.elapsedRealtime();
                Object recognizer=create(mode);
                try {
                    if(mode.equals("vad")) {
                        for(int i=0;i<runs;i++) {
                            recognizer.getClass().getMethod("reset").invoke(recognizer);
                            float[] window=new float[512];float quietMax=0;int voiced=0;
                            long start=SystemClock.elapsedRealtimeNanos(),cpu=android.os.Process.getElapsedCpuTime();
                            for(int n=0;n<100;n++)quietMax=Math.max(quietMax,(Float)recognizer.getClass().getMethod("compute",float[].class).invoke(recognizer,(Object)window));
                            if(quietMax>=0.5f)throw new AssertionError("Silence classified as speech");
                            recognizer.getClass().getMethod("reset").invoke(recognizer);
                            for(int n=0;n<pcm.length;n+=512) {
                                Arrays.fill(window,0);System.arraycopy(pcm,n,window,0,Math.min(512,pcm.length-n));
                                float probability=(Float)recognizer.getClass().getMethod("compute",float[].class).invoke(recognizer,(Object)window);
                                if(Float.isNaN(probability)||Float.isInfinite(probability))throw new AssertionError("Invalid speech probability");
                                if(probability>=0.5f)voiced++;
                            }
                            if(voiced<30)throw new AssertionError("Public speech fixture was not detected");
                            JSONObject row=new JSONObject();row.put("mode",mode);row.put("iteration",i);row.put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0);row.put("processCpuMs",android.os.Process.getElapsedCpuTime()-cpu);row.put("voicedWindows",voiced);row.put("maxQuietProbability",quietMax);row.put("pssKiB",Debug.getPss());row.put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize());
                            results.put(row);Bundle progress=new Bundle();progress.putString("measurement",row.toString());sendStatus(1,progress);
                        }
                        continue;
                    }
                    for(int i=0;i<runs;i++) {
                        long start=SystemClock.elapsedRealtimeNanos(),cpu=android.os.Process.getElapsedCpuTime();
                        Object stream=recognizer.getClass().getMethod("createStream").invoke(recognizer);
                        String text;
                        try {
                            stream.getClass().getMethod("acceptWaveform",float[].class,int.class).invoke(stream,pcm,16000);
                            if(mode.equals("lid")) text=(String)recognizer.getClass().getMethod("compute",stream.getClass()).invoke(recognizer,stream);
                            else {
                                recognizer.getClass().getMethod("decode",stream.getClass()).invoke(recognizer,stream);
                                Object result=recognizer.getClass().getMethod("getResult",stream.getClass()).invoke(recognizer,stream);
                                text=(String)result.getClass().getMethod("getText").invoke(result);
                            }
                        } finally { stream.getClass().getMethod("release").invoke(stream); }
                        long elapsed=SystemClock.elapsedRealtimeNanos()-start;
                        if(mode.equals("lid")?!"en".equals(text):!text.toLowerCase(Locale.ROOT).contains("country"))throw new AssertionError("Unexpected recognition for the required public JFK fixture: "+mode);
                        JSONObject row=new JSONObject();row.put("mode",mode);row.put("iteration",i);row.put("ms",elapsed/1_000_000.0);row.put("processCpuMs",android.os.Process.getElapsedCpuTime()-cpu);row.put("pssKiB",Debug.getPss());row.put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize());
                        results.put(row);Bundle progress=new Bundle();progress.putString("measurement",row.toString());sendStatus(1,progress);
                    }
                } finally { recognizer.getClass().getMethod("release").invoke(recognizer); }
            }
            JSONObject result=new JSONObject();result.put("status","PASS");result.put("target",getTargetContext().getPackageName());result.put("versionCode",getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),0).versionCode);result.put("debuggable",debuggable);result.put("sdk",Build.VERSION.SDK_INT);result.put("pageSize",pageSize);result.put("measurements",results);
            output.putString("result",result.toString());finish(-1,output);
        } catch(Throwable error) {
            output.putString("result","FAIL: "+error.toString());StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));output.putString("trace",trace.toString());finish(0,output);
        }
    }
    Class<?> type(String name)throws Exception {return Class.forName(prefix+name,true,targetLoader);}
    Object instance(String name)throws Exception{return type(name).getConstructor().newInstance();}
    void field(Object value,String name,Object content)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,content);}
    String path(String folder,String name)throws Exception {
        File f=new File(new File(getTargetContext().getFilesDir(),folder),name);
        if(!f.isFile()||f.length()==0)throw new AssertionError("Required installed model fixture unavailable: "+folder+"/"+name);
        return f.getAbsolutePath();
    }
    Object create(String mode)throws Exception {
        if(mode.equals("vad")) {
            Object silero=instance("SileroVadModelConfig");field(silero,"model","vad/silero_vad.onnx");
            Object config=instance("VadModelConfig");field(config,"sileroVadModelConfig",silero);field(config,"sampleRate",16000);field(config,"numThreads",1);field(config,"debug",false);
            return type("Vad").getConstructor(AssetManager.class,config.getClass()).newInstance(getTargetContext().getAssets(),config);
        }
        if(mode.equals("lid")) {
            Object whisper=instance("SpokenLanguageIdentificationWhisperConfig");field(whisper,"encoder",path("whisper-tiny","tiny-encoder.int8.onnx"));field(whisper,"decoder",path("whisper-tiny","tiny-decoder.int8.onnx"));
            Object config=instance("SpokenLanguageIdentificationConfig");field(config,"whisper",whisper);field(config,"numThreads",1);field(config,"provider","cpu");field(config,"debug",false);
            return type("SpokenLanguageIdentification").getConstructor(AssetManager.class,config.getClass()).newInstance(null,config);
        }
        Object model=instance("OfflineModelConfig");field(model,"numThreads",2);field(model,"provider","cpu");field(model,"debug",false);
        if(mode.equals("fast")) {
            Object nemo=instance("OfflineNemoEncDecCtcModelConfig");field(nemo,"model",path("fastconformer","model.int8.onnx"));field(model,"nemo",nemo);field(model,"tokens",path("fastconformer","tokens.txt"));
        } else {
            Object canary=instance("OfflineCanaryModelConfig");field(canary,"encoder",path("canary","encoder.int8.onnx"));field(canary,"decoder",path("canary","decoder.int8.onnx"));field(canary,"srcLang","en");field(canary,"tgtLang","en");field(canary,"usePnc",true);field(model,"canary",canary);field(model,"tokens",path("canary","tokens.txt"));
        }
        Object config=instance("OfflineRecognizerConfig");field(config,"modelConfig",model);
        return type("OfflineRecognizer").getConstructor(AssetManager.class,config.getClass()).newInstance(null,config);
    }
    float[] readWav(String asset)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream input=getContext().getAssets().open(asset)){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)out.write(b,0,n);}
        byte[] bytes=out.toByteArray();ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int channels=0,rate=0,bits=0,format=0;
        if(b.getInt(0)!=0x46464952||b.getInt(8)!=0x45564157)throw new AssertionError("Invalid WAV");
        for(int p=12;p+8<=bytes.length;) {
            int id=b.getInt(p),size=b.getInt(p+4);int start=p+8;
            if(size<0||start+size>bytes.length)throw new AssertionError("Truncated WAV");
            if(id==0x20746d66){format=b.getShort(start);channels=b.getShort(start+2);rate=b.getInt(start+4);bits=b.getShort(start+14);}
            if(id==0x61746164){if(format!=1||channels!=1||rate!=16000||bits!=16)throw new AssertionError("Fixture must be mono 16kHz PCM16");float[] samples=new float[size/2];for(int i=0;i<samples.length;i++)samples[i]=b.getShort(start+i*2)/32768f;return samples;}
            p=start+size+(size&1);
        }
        throw new AssertionError("WAV has no PCM data");
    }
}
