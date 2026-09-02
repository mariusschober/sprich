#!/usr/bin/env python3
"""Real microphone -> installed release IME -> a separate app's real EditText.

Play public EN/DE sentences through the Mac speaker. Reserve a quiet physical phone;
never run alongside manual editing or another speaker test. Does not inject PCM or
call Sprich APIs. Measurements include the speaker/acoustic path and VAD delay.
"""
import argparse, hashlib, json, pathlib, re, subprocess, time
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--adb',required=True)
p.add_argument('--audio-dir',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True)
p.add_argument('--version-code',type=int,required=True);p.add_argument('--count',type=int,default=200)
p.add_argument('--interval',type=float,default=9.0);p.add_argument('--tap',type=int,nargs=2,required=True)
p.add_argument('--field-tap',type=int,nargs=2,help='Actual editor tap, required on devices that ignore programmatic keyboard requests after install')
p.add_argument('--volume',type=float,default=0.5);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output([a.adb,'-s',a.serial,*args],text=True,timeout=25).strip()
def shell(*args):return adb('shell',*map(str,args))
def command(op):return shell('am','broadcast','-a','com.sprich.qa.editor.COMMAND','-p','com.sprich.qa.editor','--es','op',op)
def editor():
    result=shell('content','query','--uri','content://com.sprich.qa.editor.state/')
    return json.loads(result.split('snapshot=',1)[1])
def save(name,data): (a.output/name).write_text(json.dumps(data,ensure_ascii=False,indent=2))
def metric(index):
    row={'cue':index,'hostMonotonic':time.monotonic()}
    for name,cmd in [('memory',['dumpsys','meminfo','com.sprich.app']),('battery',['dumpsys','battery']),('thermal',['dumpsys','thermalservice']),('cpu',['dumpsys','cpuinfo']),('mic',['appops','get','com.sprich.app','RECORD_AUDIO'])]:
        try:
            text=shell(*cmd)
            if name=='cpu':text='\n'.join(x for x in text.splitlines() if 'com.sprich.app' in x or 'TOTAL:' in x or 'Load:' in x or 'CPU usage' in x)
            row[name]=text
        except Exception as error:row[name]={'status':'NOT MEASURED','error':type(error).__name__}
    with (a.output/'metrics.jsonl').open('a') as f:f.write(json.dumps(row)+'\n')
    return row
package=shell('dumpsys','package','com.sprich.app')
assert re.search(r'versionCode='+str(a.version_code)+r'\b',package),'Unexpected installed artifact'
assert not re.search(r'flags=\[[^\]]*DEBUGGABLE',package),'Release must not be debuggable'
assert shell('settings','get','secure','default_input_method')=='com.sprich.app/.input.ime.SprichIME','Sprich release is not the selected keyboard'
fixtures={lang:a.audio_dir/(lang+'.aiff') for lang in ['en','de']}
for path in fixtures.values():assert path.is_file(),str(path)
metadata={'serial':a.serial,'versionCode':a.version_code,'source':'Mac speaker -> physical microphone -> release IME -> real EditText',
 'fixtureSHA256':{k:hashlib.sha256(v.read_bytes()).hexdigest() for k,v in fixtures.items()},'volumeMultiplier':a.volume,'count':a.count,'intervalSeconds':a.interval,
 'limitations':['Synthesized public acoustic fixture, not spontaneous human speech.','Latency is playback-complete to editor callback, including endpointing.','USB charging conditions; not an unplugged battery-life measurement.']}
save('metadata.json',metadata)
command('hide');time.sleep(.5);command('reset');command('focus');time.sleep(.7)
if a.field_tap:
    shell('input','tap',*a.field_tap);time.sleep(.7)
shell('input','tap',*a.tap);time.sleep(3)
assert '(running)' in shell('appops','get','com.sprich.app','RECORD_AUDIO'),'Microphone did not start'
assert not editor()['events'],'Unexpected insertion during initial quiet window'
metrics=[metric(-1)];rows=[];seen=0;unexpected=[];cues=[];all_events=[];started=time.monotonic()
try:
    for i in range(a.count):
        scheduled=started+i*a.interval
        time.sleep(max(0,scheduled-time.monotonic()))
        lang='en' if i%2==0 else 'de'
        # Relate editor elapsedRealtime to host monotonic; report the measured USB uncertainty.
        h0=time.monotonic();device=float(shell('cat','/proc/uptime').split()[0])*1000;h1=time.monotonic()
        offset=device-(h0+h1)*500
        cueStart=time.monotonic();subprocess.run(['afplay','-v',str(a.volume),str(fixtures[lang])],check=True)
        cueEnd=time.monotonic();deadline=max(started+(i+1)*a.interval,cueEnd+3)
        new=[]
        while time.monotonic()<deadline:
            state=editor();events=state['events'];new=events[seen:]
            time.sleep(.18)
        state=editor();new=state['events'][seen:];seen=len(state['events'])
        wrong_fields=[f['field'] for f in state['fields'][1:] if f['text']]
        additions=[e['text'][e['start']:e['start']+e['added']] for e in new]
        words=' '.join(additions).lower()
        expected_words=('notebook','table') if lang=='en' else ('notizbuch','tisch')
        reasons=[]
        if len(new)!=1:reasons.append('expected one editor mutation, observed '+str(len(new)))
        if any(e['field']!=0 or e['removed']!=0 for e in new) or wrong_fields:reasons.append('cross-field/deletion mutation')
        if not all(w in words.replace(' ', '') for w in expected_words):reasons.append('required acoustic sentence not recognized')
        row={'cue':i,'language':lang,'pollingWindowNotes':reasons,'startSeconds':cueStart-started,
             'playbackSeconds':cueEnd-cueStart,'clockSyncUncertaintyMs':(h1-h0)*500+10,'events':new,
             'playbackEndToEditorMs':[e['time']-(cueEnd*1000+offset) for e in new]}
        cues.append({'language':lang,'playbackEndDeviceMs':cueEnd*1000+offset,'clockSyncUncertaintyMs':(h1-h0)*500+10})
        all_events=state['events']
        rows.append(row)
        with (a.output/'utterances.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
        save('progress.json',{'completed':len(rows),'elapsedSeconds':time.monotonic()-started,'editorMutations':len(all_events)})
        if i%10==9:
            current=metric(i);metrics.append(current)
            print(json.dumps({'completed':i+1,'elapsedSeconds':round(time.monotonic()-started),'editorMutations':len(all_events)}),flush=True)
            if '(running)' not in str(current['mic']):raise AssertionError('Microphone stopped during sustained capture')
    # A quiet recovery window also catches delayed duplicate insertions.
    time.sleep(10);all_events=editor()['events'];unexpected=all_events[a.count:]
finally:
    command('hide');time.sleep(3);metrics.append(metric(len(rows)))
    elapsed=time.monotonic()-started
    # Classify in capture order, not by arbitrary polling windows. A slow result can
    # arrive during the next cue; it is still one mutation and its full latency counts.
    evaluated=[]
    for i,cue in enumerate(cues):
        event=all_events[i] if i<len(all_events) else None
        words=event['text'][event['start']:event['start']+event['added']].lower().replace(' ','') if event else ''
        expected=('notebook','table') if cue['language']=='en' else ('notizbuch','tisch')
        ok=event is not None and event['field']==0 and event['removed']==0 and all(w in words for w in expected)
        evaluated.append({'cue':i,'language':cue['language'],'warmup':i<3,'status':'PASS' if ok else 'FAIL',
             'playbackEndToEditorMs':event['time']-cue['playbackEndDeviceMs'] if event else None,
             'clockSyncUncertaintyMs':cue['clockSyncUncertaintyMs']})
    status='PASS' if len(rows)==a.count and len(all_events)==a.count and all(r['status']=='PASS' for r in evaluated) else 'FAIL'
    save('result.json',{'status':status,'utterances':len(rows),'editorMutations':len(all_events),'elapsedSeconds':elapsed,'extraMutations':unexpected,
         'sustained30Min200Utterances':'PASS' if status=='PASS' and elapsed>=1800 and len(rows)>=200 else 'FAIL',
         'orderedResults':evaluated,'failedCues':[r['cue'] for r in evaluated if r['status']=='FAIL']})
    print(json.dumps({'status':status,'utterances':len(rows),'editorMutations':len(all_events),'elapsedSeconds':elapsed}),flush=True)
