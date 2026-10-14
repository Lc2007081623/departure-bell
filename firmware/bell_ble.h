#pragma once
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <atomic>
#include "bell_protocol.h"

constexpr const char *BLE_SERVICE="c3b10000-7a6b-4c90-8d12-123456789abc";
constexpr const char *BLE_COMMAND="c3b10001-7a6b-4c90-8d12-123456789abc";
constexpr const char *BLE_STATE="c3b10002-7a6b-4c90-8d12-123456789abc";
constexpr const char *BLE_TRACKS="c3b10003-7a6b-4c90-8d12-123456789abc";
constexpr const char *BLE_ACK="c3b10004-7a6b-4c90-8d12-123456789abc";
BellSettings bellSettings,saveSnapshot;
uint32_t savedSettings=packBellSettings({75,0,false});
bool savePending=false,settingsReady=false,bleReady=false,trackChangeRequested=false;
char playbackState='I';
int currentTrack=-1;
Preferences preferences;
QueueHandle_t commandQueue=nullptr;
NimBLEServer *bleServer=nullptr;
NimBLECharacteristic *stateCharacteristic=nullptr,*ackCharacteristic=nullptr;
std::atomic<bool> queueOverflow{false};
uint32_t lastStatePublish=0;
char lastState[21]={};

void publishState(bool force=false){
  if(!bleReady)return;
  char state[21];
  snprintf(state,sizeof(state),"v%u,m%c,t%d,p%c,d%u,s%u",bellSettings.volume,
           bellSettings.fixed?'T':'R',currentTrack,playbackState,
           packBellSettings(bellSettings)!=savedSettings,bellSettings.track);
  if(!force && (millis()-lastStatePublish<150 || strcmp(state,lastState)==0))return;
  strcpy(lastState,state);lastStatePublish=millis();
  stateCharacteristic->setValue(state);
  if(bleServer->getConnectedCount())stateCharacteristic->notify();
}
void commandAck(const char *text){
  if(!bleReady)return;
  ackCharacteristic->setValue(text);
  if(bleServer->getConnectedCount())ackCharacteristic->notify();
  Serial.printf("BLE:%s\n",text);
}
void setPlaybackState(char state,int track){
  playbackState=state;currentTrack=track;publishState(true);
}
class BellWriteCallbacks:public NimBLECharacteristicCallbacks{
  void onWrite(NimBLECharacteristic *c,NimBLEConnInfo&) override{
    auto value=c->getValue();
    BellCommand command=parseBellCommand(value.c_str(),value.length());
    // BLE callbacks never change I2S, GPIO, preferences or playback state.
    if(xQueueSend(commandQueue,&command,0)!=pdTRUE)queueOverflow=true;
  }
} bellWriteCallbacks;
class BellServerCallbacks:public NimBLEServerCallbacks{
  void onConnect(NimBLEServer *s,NimBLEConnInfo &info) override{
    s->updateConnParams(info.getConnHandle(),24,48,0,200);
  }
} bellServerCallbacks;
void serviceControl(){
  if(!bleReady)return;
  if(queueOverflow.exchange(false))commandAck("ERR:queue_full");
  BellCommand c;
  // Bound callback processing so a command flood cannot starve audio.
  for(int i=0;i<2 && xQueueReceive(commandQueue,&c,0)==pdTRUE;i++){
    char ack[21]="OK";
    switch(c.type){
      case BellCommandType::Volume:
        bellSettings.volume=c.value;snprintf(ack,sizeof(ack),"OK:V%u",c.value);break;
      case BellCommandType::Track:
        if(!bellSettings.fixed || bellSettings.track!=c.value){
          bellSettings.fixed=true;bellSettings.track=c.value;
          if(playbackState=='M')trackChangeRequested=true;
        }
        snprintf(ack,sizeof(ack),"OK:T%u",c.value);break;
      case BellCommandType::Random:
        if(bellSettings.fixed){bellSettings.fixed=false;if(playbackState=='M')trackChangeRequested=true;}
        strcpy(ack,"OK:R");break;
      case BellCommandType::Save:
        if(settingsReady){saveSnapshot=bellSettings;savePending=true;strcpy(ack,"WAIT:S");}
        else strcpy(ack,"ERR:storage");break;
      case BellCommandType::Status:publishState(true);strcpy(ack,"OK:?");break;
      default:strcpy(ack,"ERR:command");break;
    }
    commandAck(ack);publishState(true);
  }
  publishState();
}
// Only call when audio has drained to silence or is disabled. One packed NVS
// value prevents a partially saved volume/mode/track combination.
void saveSettingsIfPending(){
  if(!savePending)return;
  uint32_t packed=packBellSettings(saveSnapshot);
  bool ok=packed==savedSettings || preferences.putUInt("config",packed)==sizeof(packed);
  savePending=false;
  if(ok)savedSettings=packed;
  commandAck(ok?"OK:S":"ERR:save");publishState(true);
}
void loadBellSettings(){
  settingsReady=preferences.begin("bellble",false);
  if(settingsReady){
    uint32_t packed=preferences.getUInt("config",savedSettings);
    if(validBellSettings(packed)){bellSettings=unpackBellSettings(packed);savedSettings=packed;}
  }
}
bool beginBellBle(){
  commandQueue=xQueueCreate(8,sizeof(BellCommand));
  if(!commandQueue || !NimBLEDevice::init("DepartureBell"))return false;
  NimBLEDevice::setPower(0); // Modest TX power for nearby phone control.
  bleServer=NimBLEDevice::createServer();
  // Static callback storage must not be deleted when the server is deinitialized.
  bleServer->setCallbacks(&bellServerCallbacks,false);
  bleServer->advertiseOnDisconnect(true);
  NimBLEService *service=bleServer->createService(BLE_SERVICE);
  auto command=service->createCharacteristic(BLE_COMMAND,NIMBLE_PROPERTY::WRITE,12);
  command->setCallbacks(&bellWriteCallbacks);
  stateCharacteristic=service->createCharacteristic(BLE_STATE,NIMBLE_PROPERTY::READ|NIMBLE_PROPERTY::NOTIFY,20);
  ackCharacteristic=service->createCharacteristic(BLE_ACK,NIMBLE_PROPERTY::READ|NIMBLE_PROPERTY::NOTIFY,20);
  ackCharacteristic->setValue("READY");
  auto tracks=service->createCharacteristic(BLE_TRACKS,NIMBLE_PROPERTY::READ,256);
  String trackList;
  for(size_t i=0;i<TRACK_COUNT;i++){
    if(i)trackList+=';';
    trackList+=String(i);trackList+='=';trackList+=TRACKS[i].name;
  }
  tracks->setValue(trackList.c_str());
  if(!bleServer->start())return false;
  auto advertising=NimBLEDevice::getAdvertising();
  advertising->enableScanResponse(true);
  advertising->setName("DepartureBell");
  advertising->addServiceUUID(BLE_SERVICE);
  advertising->setMinInterval(320);advertising->setMaxInterval(640);
  bleReady=advertising->start();
  if(bleReady)publishState(true);
  return bleReady;
}
