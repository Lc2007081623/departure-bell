# 发车铃安卓控制应用

安装文件：`output/DepartureBell-1.0.0.apk`。安卓8.0及以上，原生Java界面，无第三方运行库，不需要联网。

## 手机使用

1. 将APK传到安卓手机，点击安装。系统如要求允许此次文件来源安装应用，按提示开启后返回安装。
2. 先断开nRF Connect与发车铃的连接。打开“发车铃”应用，允许附近设备权限。安卓8～11扫描还需要定位权限和系统定位开关，应用不会读取位置。
3. 保持实体开关ON，点击“连接发车铃”。也可以在警示结束后的60秒内连接。
4. 松开音量滑块才发送音量；可点击75%或静音快捷按钮。四首曲目按钮固定选曲，随机按钮恢复随机模式。
5. 点击“保存当前设置”。音乐播放期间会等当前段结束再保存，应用收到OK:S才显示保存成功；空闲期间立即保存。
6. 设备休眠会断开，应用显示提示。重新按下实体开关，再点击连接。

实体开关仍负责开始音乐、停止并播放警示。APK不能发送手机音频，也不能更新设备固件。

## 验证情况

- 使用官方Android API35编译，最低API26；APK v2/v3签名及ZIP对齐校验通过。
- BellStateTest检查真实协议格式、警示期间实际曲目/待选曲区分、0～100音量和无效命令拒绝。
- BLE读写串行队列：先订阅状态和ACK，再读取当前状态，完成后开放控制；使用新版API33安全回调并兼容旧版。
- 连接/请求超时、扫描权限拒绝、蓝牙关闭、休眠断开均给出提示。
- 用户安装使用后反馈正常。电脑已对固件BLE协议进行实物检查；本地串口日志未纳入仓库。

## 重新构建

```powershell
python setup_tools.py
python build.py
```

脚本下载Microsoft JDK17、官方Android API35和build-tools35到本项目tools目录，不修改系统JAVA_HOME。无需Gradle。构建时运行协议测试，编译DEX并生成签名APK、apk-info.txt和build-report.json。

`signing/bell-control.p12`为本项目本地签名密钥，更新APK要保留同一密钥；它不包含在源码压缩包内。此包用于本机项目安装，不涉及应用商店发布。

参考：[安卓蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)、[GATT回调](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback)。
