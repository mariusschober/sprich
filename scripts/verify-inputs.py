#!/usr/bin/env python3
"""Check checked-in runtime bytes, archive pins and actual release dependency notices."""
import argparse,hashlib,pathlib,re,zipfile,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--archives',type=pathlib.Path);p.add_argument('--inventory',type=pathlib.Path);a=p.parse_args()
def require(value,message):
    if not value:raise SystemExit('FAIL: '+message)
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
aar=root/'app/libs/sherpa-onnx-1.13.6-asr-arm64.aar'
require(aar.is_file(),'recognition-only AAR is missing')
require(sha(aar)=='87897d9bc5c74356404b9c14e01f6478e46ddff3a6bc6932b5fb7affea318f6b','native AAR changed without renewed provenance/acceptance')
with zipfile.ZipFile(aar) as z:
    expected={'jni/arm64-v8a/libonnxruntime.so':'dc5e4c172b1be9e530c6a62ad8f1be3e0a911cabdee6195abf28dab72477e194',
              'jni/arm64-v8a/libsherpa-onnx-jni.so':'7a58931c7324c077c663b0c90eaa9863061d80bd6c990913399da1696456b9d9',
              'classes.jar':'b6df872b28c2bdda146361be664ea42ad4ad70987f0d37ce6ec8115519f410ec'}
    require({n for n in z.namelist() if n.endswith('.so')}=={n for n in expected if n.endswith('.so')},'unexpected native AAR contents')
    for name,digest in expected.items():require(hashlib.sha256(z.read(name)).hexdigest()==digest,'native input hash mismatch: '+name)
print('PASS: recognition-only ARM64 runtime inputs match reviewed provenance')
source=(root/'app/src/main/java/com/sprich/app/models/manager/ModelManifest.kt').read_text().split(') + if (com.sprich.app.BuildConfig.DEBUG)')[0]
entries=re.findall(r'ModelEntry\(\s*id = "([^"]+)".*?sourceUrl = "([^"]+)".*?sizeBytes = (\d+)L.*?sha256 = "([0-9a-f]+)"',source,re.S)
require({e[0] for e in entries}=={'accurate','lid','fastconformer'},'unexpected production model manifest')
for name,url,size,digest in entries:
    require(len(digest)==64 and len(set(digest))>10,'missing archive hash: '+name)
    require(url.startswith('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/'),'unexpected model source: '+name)
    require(int(size)>1_000_000,'missing measured archive size: '+name)
    if a.archives:
        path=a.archives/url.rsplit('/',1)[1]
        require(path.is_file(),'required model archive unavailable: '+str(path))
        require(path.stat().st_size==int(size) and sha(path)==digest,'archive integrity failed: '+name)
        print('PASS: actual archive SHA-256 and size: '+name)
print('PASS: production model manifest has complete HTTPS/size/hash pins')
if not a.archives:print('MODEL_ARCHIVE_DOWNLOAD: NOT MEASURED by this input-only gate')
require((root/'licenses/THIRD_PARTY_NOTICES.txt').read_bytes()==(root/'app/src/main/assets/THIRD_PARTY_NOTICES.txt').read_bytes(),'packaged notices differ from reviewed notices')
if a.inventory:
    require(a.inventory.is_file(),'resolved release dependency inventory missing')
    require(set(a.inventory.read_text().splitlines())==set((root/'licenses/runtime-dependencies.tsv').read_text().splitlines()),'release dependencies changed; regenerate and review licenses/notices')
    print('PASS: actual resolved release dependencies match the notices inventory')

require(sha(root/'app/src/main/assets/vad/silero_vad.onnx')=='9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6','bundled VAD model integrity')
print('PASS: bundled Silero VAD matches the official asset digest')

# Fail if a new file can escape the no-backup policy through an OEM transfer.
backup_domains = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"}
legacy = ET.parse(root / "app/src/main/res/xml/backup_rules.xml").getroot()
modern = ET.parse(root / "app/src/main/res/xml/data_extraction_rules.xml").getroot()
for rules in [legacy, modern.find("cloud-backup"), modern.find("device-transfer")]:
    require(rules is not None, "backup/transfer policy is missing")
    require(not rules.findall("include"), "no-backup policy contains an inclusion")
    require({e.get("domain") for e in rules.findall("exclude") if e.get("path") == "."} == backup_domains, "backup policy must exclude every app storage domain")
print("PASS: backup and device transfer exclude every app storage domain")
