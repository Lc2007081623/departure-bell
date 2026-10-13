import concurrent.futures
import pathlib
import urllib.request
import zipfile

root=pathlib.Path(__file__).parent/'tools'
root.mkdir(exist_ok=True)
packages={
 'jdk': 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip',
 'platform': 'https://dl.google.com/android/repository/platform-35_r02.zip',
 'build-tools': 'https://dl.google.com/android/repository/build-tools_r35_windows.zip',
}
def install(item):
 name,url=item
 target=root/name
 if target.exists():return str(target)+' already installed'
 archive=root/(name+'.zip')
 urllib.request.urlretrieve(url,archive)
 with zipfile.ZipFile(archive) as z:z.extractall(target)
 return str(target)+' installed'
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
 for result in executor.map(install,packages.items()):print(result,flush=True)
