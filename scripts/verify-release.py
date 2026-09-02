#!/usr/bin/env python3
"""Fail closed on the actual release APK; no device/runtime claims are inferred."""
import argparse,hashlib,os,pathlib,re,struct,subprocess,sys,zipfile
p=argparse.ArgumentParser();p.add_argument('apk',type=pathlib.Path);p.add_argument('--sdk',default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'));a=p.parse_args()
def require(condition,message):
    if not condition:raise SystemExit('FAIL: '+message)
require(a.apk.is_file(),'release APK is missing')
expected={'libandroidx.graphics.path.so','libdatastore_shared_counter.so','libonnxruntime.so','libsherpa-onnx-jni.so'}
with zipfile.ZipFile(a.apk) as z, a.apk.open('rb') as raw:
    names=z.namelist();libs=[n for n in names if n.endswith('.so')]
    require({pathlib.PurePosixPath(n).name for n in libs}==expected,'unexpected or missing native libraries')
    require(all(n.startswith('lib/arm64-v8a/') for n in libs),'unsupported ABI packaged')
    require('assets/THIRD_PARTY_NOTICES.txt' in names,'notices missing')
    require(len(z.read('assets/THIRD_PARTY_NOTICES.txt'))>100_000,'notices are incomplete')
    require(not any(re.search(r'\.(onnx|gguf|wav|aiff|bin)$',n,re.I) for n in names if n.startswith('assets/') and n != 'assets/vad/silero_vad.onnx'),'model/test fixture bundled in production assets')
    require(hashlib.sha256(z.read('assets/vad/silero_vad.onnx')).hexdigest()=='9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6','bundled VAD model integrity')
    for name in libs:
        info=z.getinfo(name);data=z.read(name)
        require(info.compress_type==zipfile.ZIP_STORED,'native library is compressed: '+name)
        raw.seek(info.header_offset);header=raw.read(30);name_len,extra_len=struct.unpack_from('<HH',header,26)
        offset=info.header_offset+30+name_len+extra_len
        require(offset%16384==0,'native ZIP offset is not 16 KB aligned: '+name)
        require(data[:6]==b'\x7fELF\x02\x01','not little-endian ELF64: '+name)
        require(struct.unpack_from('<H',data,18)[0]==183,'not ARM64: '+name)
        phoff=struct.unpack_from('<Q',data,32)[0];entsize,count=struct.unpack_from('<HH',data,54)
        loads=0;relro=False;stack=False
        for i in range(count):
            typ,flags,off,addr,_,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',data,phoff+i*entsize)
            if typ==1:
                loads+=1;require(align>=16384 and off%align==addr%align,'ELF LOAD alignment below 16 KB: '+name)
            if typ==0x6474e552:relro=True
            if typ==0x6474e551:stack=True;require(not flags&1,'executable stack: '+name)
        require(loads>0 and relro and stack,'missing LOAD/RELRO/stack security metadata: '+name)
        require(not any(token in data.lower() for token in [b'espeak_ng_',b'espeak-ng',b'piper_phonemize',b'espeak_initialize']),'unused TTS code found: '+name)
        print('PASS:',name,'ELF + ZIP 16 KB, RELRO, NX stack, sha256='+hashlib.sha256(data).hexdigest())
    dex=b''.join(z.read(n) for n in names if re.fullmatch(r'classes\d*\.dex',n))
    for token in [b'Lcom/sprich/app/ui/benchmark/BenchmarkActivity;',b'Lcom/sprich/qa/',b'sherpa-onnx-nemotron-3.5-asr-streaming',b'jfk.wav']:
        require(token not in dex,'production contains a debug/experimental surface: '+token.decode())
require(a.sdk,'set ANDROID_SDK_ROOT or use --sdk to verify the merged manifest')
sdk=pathlib.Path(a.sdk);tool=sdk/'build-tools/36.0.0/aapt2';require(tool.is_file(),'build tools 36.0.0 aapt2 missing')
manifest=subprocess.check_output([str(tool),'dump','xmltree',str(a.apk),'--file','AndroidManifest.xml'],text=True)
require('android:debuggable' not in manifest or not re.search(r'android:debuggable[^\n]*0xffffffff',manifest),'debuggable release')
require(re.search(r'android:allowBackup[^\n]*0x0',manifest),'backup is not explicitly disabled')
require(re.search(r'android:usesCleartextTraffic[^\n]*0x0',manifest),'cleartext is not explicitly disabled')
require('android.permission.BIND_INPUT_METHOD' in manifest,'IME service binding permission missing')
require('BenchmarkActivity' not in manifest and 'NativeRuntimeCheck' not in manifest,'debug component in release manifest')
permissions=set(re.findall(r'android:name[^\n]*="(android.permission.[^"]+)"',manifest))
allowed={'android.permission.RECORD_AUDIO','android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.VIBRATE','android.permission.BIND_INPUT_METHOD'}
require(permissions<=allowed,'unexpected permission: '+str(permissions-allowed))
print('PASS: release manifest, production assets and native contents')
print('APK_SHA256='+hashlib.sha256(a.apk.read_bytes()).hexdigest())
print('16_KB_RUNTIME: NOT MEASURED by this host-only gate')
