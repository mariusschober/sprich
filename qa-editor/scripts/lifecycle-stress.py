#!/usr/bin/env python3
"""Actual physical IME start/cancel/field/password/hide cycles, with editor and mic assertions."""
import argparse,json,pathlib,re,shlex,subprocess,time
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--adb',required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--version-code',type=int,required=True);p.add_argument('--count',type=int,default=100);p.add_argument('--mic',nargs=2,type=int,required=True);p.add_argument('--field',nargs=2,type=int,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def shell(*args):return subprocess.check_output([a.adb,'-s',a.serial,'shell',shlex.join(map(str,args))],text=True,timeout=20).strip()
def command(op,field=0,**extras):
    args=['am','broadcast','-a','com.sprich.qa.editor.COMMAND','-p','com.sprich.qa.editor','--es','op',op,'--ei','field',field]
    for k,v in extras.items():args += ['--ei' if isinstance(v,int) else '--es',k,v]
    return shell(*args)
def state():return json.loads(shell('content','query','--uri','content://com.sprich.qa.editor.state/').split('snapshot=',1)[1])
def running():return '(running)' in shell('appops','get','com.sprich.app','RECORD_AUDIO')
def waitShown(expected):
    deadline=time.monotonic()+4
    while True:
        shown='mInputShown=true' in shell('dumpsys','input_method')
        if shown==expected:return
        if time.monotonic()>=deadline:raise AssertionError('Host editor keyboard visibility did not settle: '+str(expected))
        if expected:
            command('focus');shell('input','tap',*a.field)
        time.sleep(.1)
def save(n,v):(a.output/n).write_text(json.dumps(v,ensure_ascii=False,indent=2))
package=shell('dumpsys','package','com.sprich.app');assert re.search(r'versionCode='+str(a.version_code)+r'\b',package);assert not re.search(r'flags=\[[^\]]*DEBUGGABLE',package)
rows=[];initial=None;error=None;started=time.monotonic()
try:
    shell('am','start','-n','com.sprich.qa.editor/.EditorActivity');time.sleep(1)
    command('native');command('hide');command('reset');command('focus');shell('input','tap',*a.field);time.sleep(1);shell('input','tap',*a.mic)
    deadline=time.monotonic()+20
    while not running() and time.monotonic()<deadline:time.sleep(.2)
    assert running(),'Warm-up microphone did not start'
    command('hide');time.sleep(1);assert not running(),'Warm-up cancellation left microphone running'
    command('reset');initial=state();save('before.json',initial)
    for i in range(a.count):
        command('focus');shell('input','tap',*a.field);waitShown(True)
        # Mutate the editor only while recording is off. These are explicit QA setup events.
        command('replace',text='Cursor test',cursor=11)
        time.sleep(.35) # Let editor selection callbacks and IME insets finish before targeting its control.
        before=state();base=len(before['events'])
        startSent=time.monotonic();shell('input','tap',*a.mic)
        startDeadline=time.monotonic()+4
        captureStarted=running()
        while not captureStarted and time.monotonic()<startDeadline:
            time.sleep(.05);captureStarted=running()
        startObservedMs=(time.monotonic()-startSent)*1000
        if not captureStarted:
            (a.output/('start-failed-'+str(i)+'.png')).write_bytes(subprocess.check_output([a.adb,'-s',a.serial,'exec-out','screencap','-p'],timeout=15))
        kind=['field','hide','password','pin','cursor'][i%5]
        begin=time.monotonic()
        if kind=='field':command('focus',field=1)
        elif kind=='hide':command('hide')
        elif kind=='password':command('focus',field=2);shell('input','tap',*a.mic)
        elif kind=='pin':command('focus',field=3);shell('input','tap',*a.mic)
        else:command('select',start=0,end=6)
        deadline=time.monotonic()+2
        while running() and time.monotonic()<deadline:time.sleep(.05)
        micAfter=running();after=state();events=after['events'][base:]
        row={'cycle':i,'kind':kind,'captureStarted':captureStarted,'startObservedMs':startObservedMs,'micAfterCancellation':micAfter,'cancellationCheckMs':(time.monotonic()-begin)*1000,'editorMutations':events,'status':'PASS' if captureStarted and not micAfter and not events else 'FAIL'}
        rows.append(row);save('progress.json',{'completed':len(rows),'failures':sum(r['status']=='FAIL' for r in rows),'elapsedSeconds':time.monotonic()-started})
        with (a.output/'cycles.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
        command('hide');waitShown(False)
        if i%20==19:
            shell('input','keyevent',3);shell('am','start','-n','com.sprich.qa.editor/.EditorActivity');time.sleep(.4)
        if i%10==9:print(json.dumps({'completed':i+1,'failures':sum(r['status']=='FAIL' for r in rows)}),flush=True)
    time.sleep(3)
except Exception as e:error=repr(e)
finally:
    command('hide')
    save('result.json',{'status':'PASS' if len(rows)==a.count and all(r['status']=='PASS' for r in rows) and error is None else 'FAIL','serial':a.serial,'versionCode':a.version_code,'cycles':len(rows),'elapsedSeconds':time.monotonic()-started,'error':error,'failedCycles':[r['cycle'] for r in rows if r['status']=='FAIL'],'scope':'Physical microphone/control lifecycle and real EditText events. No transcript-quality or sustained decoding claim.'})
