"""Build a small native APK using official Android build tools, without Gradle."""
import hashlib
import json
import os
import pathlib
import subprocess
import zipfile

root=pathlib.Path(__file__).resolve().parent
os.chdir(root)
def find(folder,filename):
 matches=list((root/'tools'/folder).rglob(filename))
 if not matches:raise RuntimeError('Missing '+filename+'; run setup_tools.py first')
 return matches[0]
javac=find('jdk','javac.exe');java=javac.with_name('java.exe');keytool=javac.with_name('keytool.exe')
aapt=find('build-tools','aapt2.exe');align=aapt.with_name('zipalign.exe')
android=find('platform','android.jar')
build=root/'build';build.mkdir(exist_ok=True)
for folder in ['classes','gen','dex','test-classes']:(build/folder).mkdir(exist_ok=True)
def run(*args):
 subprocess.run([str(a) for a in args],check=True)
def relative(path):return str(path.relative_to(root))
run(aapt,'compile','--dir','res','-o','build/resources.zip')
run(aapt,'link','-o','build/resources.apk','--manifest','AndroidManifest.xml','-I',relative(android),'--java','build/gen','--auto-add-overlay','build/resources.zip')
sources=list(pathlib.Path('src').rglob('*.java'))+list(pathlib.Path('build/gen').rglob('*.java'))
run(javac,'-encoding','UTF-8','--release','8','-classpath',relative(android),'-d','build/classes',*sources)
run(javac,'-encoding','UTF-8','--release','8','-classpath','build/classes','-d','build/test-classes','tests/BellStateTest.java')
run(java,'-classpath','build/classes;build/test-classes','BellStateTest')
classes=list(pathlib.Path('build/classes').rglob('*.class'))
run(java,'-classpath',relative(aapt.parent/'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','26','--lib',relative(android),'--output','build/dex',*classes)
unsigned=build/'unsigned.apk'
unsigned.write_bytes((build/'resources.apk').read_bytes())
with zipfile.ZipFile(unsigned,'a') as archive:
 for dex in (build/'dex').glob('*.dex'):archive.write(dex,dex.name)
run(align,'-f','4','build/unsigned.apk','build/aligned.apk')
signing=root/'signing';signing.mkdir(exist_ok=True)
os.environ['BELL_KEY_PASSWORD']='DepartureBellLocalBuild2026'
if not (signing/'bell-control.p12').exists():
 run(keytool,'-genkeypair','-keystore','signing/bell-control.p12','-storetype','PKCS12','-storepass:env','BELL_KEY_PASSWORD','-keypass:env','BELL_KEY_PASSWORD','-alias','bell-control','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Departure Bell Local Build')
output=root/'output';output.mkdir(exist_ok=True)
apk='output/DepartureBell-1.0.0.apk'
run(java,'-jar',relative(aapt.parent/'lib/apksigner.jar'),'sign','--ks','signing/bell-control.p12','--ks-pass','env:BELL_KEY_PASSWORD','--key-pass','env:BELL_KEY_PASSWORD','--out',apk,'build/aligned.apk')
run(java,'-jar',relative(aapt.parent/'lib/apksigner.jar'),'verify','--verbose','--print-certs',apk)
run(align,'-c','4',apk)
badging=subprocess.check_output([str(aapt),'dump','badging',apk],text=True,encoding='utf-8')
(output/'apk-info.txt').write_text(badging,encoding='utf-8')
report={'apk':apk,'bytes':pathlib.Path(apk).stat().st_size,'sha256':hashlib.sha256(pathlib.Path(apk).read_bytes()).hexdigest(),'minAndroid':'8.0 (API 26)','targetSdk':35,'version':'1.0.0','package':'cn.departurebell.control','verification':'state/command tests, APK signature and ZIP alignment PASS','phoneBleTest':'pending installation on user Android phone'}
(output/'build-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
