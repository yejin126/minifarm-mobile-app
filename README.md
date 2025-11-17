## Mobile App

Mobile dashboard for the TinyIoT smart-farm system. It shows real-time sensor data, AI-based plant health inference, GPS location, and a live Raspberry Pi camera stream in a single mobile interface.

## How to Run

To build this project, you must replace the placeholder values in the following files:

**1. app/src/main/java/com/example/tiny2/network/TinyIoTApi.kt**

BASE: Set to your TinyIoT CSE Server URL

**2. app/src/main/java/com/example/tiny2/monitor/DeviceMonitorViewModel.kt**

mqttCfg.host: Set to your MQTT Broker IP

**3. app/src/main/java/com/example/tiny2/MainActivity.kt**

cameraUrl: Set to your camera stream URL

**4. app/src/main/java/com/example/tiny2/network/OneM2M.kt**

BASE: Set to your TinyIoT CSE Server URL

**5. app/src/main/res/xml/network_security_config.xml**

Uncomment and add your IPs to allow HTTP traffic:

```
<domain includeSubdomains="true">YOUR_CSE_SERVER_IP_HERE</domain>
<domain includeSubdomains="true">YOUR_CAMERA_IP_HERE</domain>
```

