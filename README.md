# 发车铃 · Departure Bell

ESP32-C3 SuperMini + NS4168 的自锁开关发车铃，带安卓 BLE 控制应用。

## 功能

- 按下自锁开关：随机选择一首音乐并循环播放，也可通过手机指定固定曲目。
- 解除自锁：音乐淡出，播放当前歌曲对应的警示音一次。
- 警示期间再次按下开关：先播完警示，再恢复音乐。
- 警示结束后关闭功放，蓝牙继续保持 **60秒**；期间再次ON立即播放，否则进入深睡。
- 安卓应用可调音量、固定选曲、恢复随机和保存设置；默认75%音量、随机模式。
- 音乐播放时等当前段结束再保存；空闲时立即保存。应用收到成功回复才显示保存完成。

## 安卓应用

[下载 APK](android-app/output/DepartureBell-1.0.0.apk?raw=true)，支持安卓8.0及以上。也可在本仓库 Releases 下载。

安装后允许蓝牙权限，保持开关ON，点击“连接发车铃”。先断开nRF Connect等其他控制应用。设备名是 **DepartureBell**。手机与设备之间是BLE控制连接，不传输手机音乐。

[安卓使用与构建说明](android-app/README.md) · [BLE协议及nRF Connect操作](蓝牙控制.md)

## 硬件

- ESP32-C3 SuperMini：板载4MB Flash，板载稳压器输出3.3V。
- NS4168功放：实际供电3.3V，喇叭8Ω / 2W。
- 单节带保护锂电池，FP6298升压到5V，给开发板供电。
- 自锁开关接GPIO0，闭合拉低。外部100k上拉至3.3V、1k串联、100nF消抖。

| GPIO | 功能 |
| --- | --- |
| 0 | 自锁开关，LOW为ON |
| 4 | 功放CTRL |
| 5 | I²S LRCLK |
| 6 | I²S BCLK |
| 7 | I²S SDATA |
| 8 | 板载蓝灯，高电平关闭 |

普通USB连接电脑时，先断开电池供电。此硬件没有USB/电池电源隔离。深睡关闭功放和蓝牙，但升压、板载电源灯和稳压器仍有静态耗电。

## 音频

`bell/`保留原始MP3；文件名括号内数字表示警示开始的秒数。

| 曲目编号 | 文件 | 音乐 / 警示分界 |
| --- | --- | --- |
| 0 | etchujima（24）.mp3 | 24秒 |
| 1 | inage-kaigan（11）.mp3 | 11秒 |
| 2 | JK03-south（18）.mp3 | 18秒 |
| 3 | JT03_SGW（17）.mp3 | 17秒 |

音乐与警示共八段，分别进行两遍响度归一化（目标-20 LUFS、编码前真峰值上限-3dBTP），再转为22050Hz单声道4位IMA ADPCM。播放时解码成16位PCM，通过双声道I²S发送相同样本。首尾和停止转换使用约5ms淡入淡出。每次启动校验音频CRC，失败则禁用播放。

`generated/audio.bin`为可直接烧录的音频映像，`manifest.json`记录分段、偏移和源文件SHA256。`verify_audio.py`检查解码、CRC、分段和响度，实际解码后的八段约-19.99到-19.88 LUFS。

## 固件构建与烧录

依赖：Python3.12（audioop）、FFmpeg、Arduino CLI、ESP32 Arduino3.3.10、NimBLE-Arduino2.5.1。Python3.13起移除audioop，本脚本使用3.12。

```powershell
arduino-cli core update-index --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core install esp32:esp32@3.3.10 --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli lib install NimBLE-Arduino@2.5.1
# arduino-cli、ffmpeg和python加入PATH后：
.\build_upload.ps1
.\build_upload.ps1 -Upload -Port COM3
# 也可显式传入工具路径：
.\build_upload.ps1 -ArduinoCli 'C:\tools\arduino-cli.exe' -Esptool 'C:\tools\esptool.exe'
```

烧录需要esptool；脚本可查找PATH或本机Arduino15中的已安装工具。

| 分区 | 偏移 | 大小 |
| --- | --- | --- |
| NVS | 0x9000 | 20KiB |
| 应用 | 0x10000 | 960KiB |
| 音频 | 0x100000 | 3MiB |

无OTA。当前应用613840字节，音频975049字节。必要时按住BOOT、按RESET、松开BOOT进入下载模式；上传后单独按RESET运行程序。

## 项目结构与验证

| 路径 | 内容 |
| --- | --- |
| `firmware/` | ESP32-C3固件、BLE协议、自定义分区 |
| `android-app/` | 原生Java安卓应用、构建脚本、APK |
| `hardware/` | v6 PCB的Gerber生产文件和BOM（2026-10-13导出） |
| `bell/` | 原始音频 |
| `generated/` | 音频映像、分段与响度数据 |
| `ble_control.html` | 电脑Chrome/Edge用Web Bluetooth控制页 |
| `ble_client.py` | 电脑BLE命令和实物协议测试（依赖bleak） |

安卓应用已编译并通过协议测试、APK签名和对齐校验，用户反馈使用正常。固件实物检查了音乐循环、对应警示、BLE音量/选曲/随机、保存、再次唤醒连接，以及60秒空闲后的休眠。网页控制可运行 `node test_ble_control.cjs` 检查。

本地签名密钥、完整Flash备份、开发工具及串口日志不纳入仓库。当前为个人DIY项目。

## PCB生产文件

- [Gerber ZIP](hardware/Gerber_PCB1_2026-10-13.zip)：v6 PCB生产文件。
- [BOM Excel](hardware/BOM_Board1_PCB1_2026-10-13.xlsx)：对应元器件清单。

文件按原始导出内容上传，未修改。可编辑EDA工程和原理图尚未纳入仓库。

## 许可证

本项目的原创固件代码、安卓应用代码、脚本、文档和硬件设计文件采用 [MIT License](LICENSE)，版权署名为 `2026 Lc2007081623`。

音乐文件 `bell/*.mp3`、由它们生成的 `generated/audio.bin` 以及发布包中的音频内容**不包含在MIT授权内**，其权利归各自权利人所有。第三方库、框架及其编译内容保留各自的许可证；本项目的MIT许可证不替代这些授权。
