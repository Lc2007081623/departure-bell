"""Package existing verified firmware artifacts; never include NVS/key backups."""
import hashlib
import json
import pathlib
import zipfile

root=pathlib.Path(__file__).resolve().parent
output=root/'generated'/'releases'
output.mkdir(exist_ok=True)
files=['firmware.ino.bootloader.bin','firmware.ino.partitions.bin','firmware.ino.bin','audio.bin']
checksums={name:hashlib.sha256((root/'generated'/name).read_bytes()).hexdigest() for name in files}
if (root/'generated/firmware.ino.bin').stat().st_size>0xF0000:raise ValueError('Application exceeds 960 KiB partition')
instructions='''Departure Bell firmware 1.0.0 / ESP32-C3 SuperMini, 4MB Flash

Disconnect the battery before connecting ordinary USB.
Hold BOOT, press RESET, then release BOOT to enter download mode if needed.

Install Python and esptool, then run in this folder (replace COM3 as needed):
python -m pip install esptool
python -m esptool --chip esp32c3 --port COM3 --baud 460800 write-flash --flash-size 4MB 0x0 firmware.ino.bootloader.bin 0x8000 firmware.ino.partitions.bin 0x10000 firmware.ino.bin 0x100000 audio.bin

Press RESET without BOOT after upload.
This writes bootloader, partitions, app and audio. It does not erase NVS settings.
Default settings: 75% volume, random selection. BLE name: DepartureBell.
Physical OFF plays the matching warning, then keeps BLE for 60 seconds before deep sleep.
No OTA support. See the repository README for wiring and BLE protocol.
'''
archive=output/'DepartureBell-firmware-1.0.0.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for name in files:z.write(root/'generated'/name,name)
 z.writestr('README.txt',instructions)
 z.writestr('SHA256.json',json.dumps(checksums,indent=2))
 z.write(root/'LICENSE','LICENSE')
 z.writestr('LICENSE_SCOPE.txt','Project original code, documentation and hardware design files: MIT.\nAudio content (audio.bin and source MP3 files) is excluded from MIT; rights belong to the respective rights holders.\nThird-party libraries/frameworks retain their own licenses; MIT does not replace those licenses.\n')
with zipfile.ZipFile(archive) as z:
 assert z.testzip() is None
 for name,digest in checksums.items():assert hashlib.sha256(z.read(name)).hexdigest()==digest
print(str(archive))
print('Firmware release archive SHA256 and CRC checks PASS')
