#include <Arduino.h>
#include <driver/i2s_std.h>
#include <driver/gpio.h>
#include <esp_sleep.h>
#include <esp_random.h>
#include <esp_partition.h>
#include <esp_rom_crc.h>
#include <bootloader_random.h>
#include "audio_manifest.h"
#include "bell_ble.h"

constexpr int BUTTON=0, CTRL=4;
constexpr uint32_t RATE=22050;
int volumeGain=75*256;
constexpr uint32_t FRAMES=64, DMA_BUFFERS=3, FADE_FRAMES=RATE*5/1000;
const esp_partition_t *audioPartition;
i2s_chan_handle_t tx=nullptr;
bool stableOn=false, rawOn=false;
uint32_t rawChanged=0;
bool audioError=false;
const int STEP[89]={7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767};
const int INDEX[8]={-1,-1,-1,-1,2,4,6,8};

bool buttonOn(){
  bool r=digitalRead(BUTTON)==LOW;
  if(r!=rawOn){rawOn=r;rawChanged=millis();}
  if(millis()-rawChanged>=15) stableOn=rawOn;
  return stableOn;
}
void stopAudio(){
  digitalWrite(CTRL,LOW);
  if(tx){i2s_channel_disable(tx);i2s_del_channel(tx);tx=nullptr;}
}
bool startAudio(){
  i2s_chan_config_t c=I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0,I2S_ROLE_MASTER);
  c.dma_desc_num=DMA_BUFFERS;c.dma_frame_num=FRAMES;
  c.auto_clear_after_cb=true; // Underruns send silence instead of repeating old audio.
  if(i2s_new_channel(&c,&tx,nullptr)!=ESP_OK) return false;
  i2s_std_config_t s={};
  s.clk_cfg=I2S_STD_CLK_DEFAULT_CONFIG(RATE);
  s.slot_cfg=I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT,I2S_SLOT_MODE_STEREO);
  s.gpio_cfg.mclk=I2S_GPIO_UNUSED;s.gpio_cfg.bclk=GPIO_NUM_6;
  s.gpio_cfg.ws=GPIO_NUM_5;s.gpio_cfg.dout=GPIO_NUM_7;s.gpio_cfg.din=I2S_GPIO_UNUSED;
  if(i2s_channel_init_std_mode(tx,&s)!=ESP_OK || i2s_channel_enable(tx)!=ESP_OK){stopAudio();return false;}
  digitalWrite(CTRL,HIGH);
  return true;
}
int16_t decode(uint8_t code,int &pred,int &idx){
  int step=STEP[idx],d=step>>3;
  if(code&1)d+=step>>2;if(code&2)d+=step>>1;if(code&4)d+=step;
  pred=constrain(pred+((code&8)?-d:d),-32768,32767);
  idx=constrain(idx+INDEX[code&7],0,88);
  return pred;
}
// Finish OFF with a 5ms fade using the next real samples. Small DMA buffers
// bound the queued music tail; warning follows on the same running channel.
bool playSegment(const Segment &s,bool interruptible){
  int pred=0,idx=0;
  uint32_t pos=0;
  bool fading=false;
  uint32_t fadeLeft=FADE_FRAMES;
  uint8_t packed[FRAMES/2];int16_t stereo[FRAMES*2];
  while(pos<s.samples){
    serviceControl();
    bool on=buttonOn();
    if(interruptible&&(!on || trackChangeRequested))fading=true;
    uint32_t n=min(FRAMES,s.samples-pos);
    if(fading)n=min(n,fadeLeft);
    if(esp_partition_read(audioPartition,s.offset+pos/2,packed,(n+1)/2)!=ESP_OK){audioError=true;return false;}
    for(uint32_t i=0;i<n;i++){
      uint8_t code=(i&1)?packed[i/2]&15:packed[i/2]>>4;
      int32_t sample=decode(code,pred,idx);
      int target=int(bellSettings.volume)*256;
      // Smooth remote gain changes over at most about 73ms, including mute.
      if(volumeGain<target)volumeGain=min(volumeGain+16,target);
      else if(volumeGain>target)volumeGain=max(volumeGain-16,target);
      sample=sample*volumeGain/(100*256);
      uint32_t edge=min(pos+i,s.samples-1-pos-i);
      if(edge<FADE_FRAMES)sample=sample*int32_t(edge)/int32_t(FADE_FRAMES);
      if(fading)sample=sample*int32_t(fadeLeft-i-1)/int32_t(FADE_FRAMES);
      stereo[2*i]=stereo[2*i+1]=int16_t(sample);
    }
    size_t done=0;
    if(i2s_channel_write(tx,stereo,n*4,&done,25)!=ESP_OK||done!=n*4){audioError=true;return false;}
    pos+=n;
    if(fading){fadeLeft-=n;if(!fadeLeft)return false;}
  }
  return !fading;
}
bool drainToSilence(){
  int16_t silence[FRAMES*2]={};
  // More silence than the complete DMA capacity guarantees queued audio has
  // played before CTRL is muted; poll the switch throughout the drain.
  for(uint32_t i=0;i<DMA_BUFFERS+1;i++){
    size_t done=0;
    buttonOn();
    serviceControl();
    if(i2s_channel_write(tx,silence,sizeof(silence),&done,25)!=ESP_OK ||
       done!=sizeof(silence)){audioError=true;return false;}
  }
  return true;
}
void sleepNow(){
  stopAudio();
  serviceControl();saveSettingsIfPending();
  setPlaybackState('I',-1);
  if(bleReady){bleReady=false;NimBLEDevice::deinit(true);}
  preferences.end();
  // All I2S outputs quiet; external pull-up on GPIO0 remains powered.
  for(int p:{5,6,7}){pinMode(p,OUTPUT);digitalWrite(p,LOW);}
  gpio_hold_en(GPIO_NUM_4);gpio_deep_sleep_hold_en();
  Serial.println("SLEEP:GPIO0_LOW_WAKE");Serial.flush();
  esp_deep_sleep_enable_gpio_wakeup(1ULL<<BUTTON,ESP_GPIO_WAKEUP_GPIO_LOW);
  esp_deep_sleep_start();
}
void waitForSwitchOrSleep(){
  stopAudio();
  setPlaybackState('I',-1);
  constexpr uint32_t BLE_IDLE_MS=60000;
  uint32_t idleStarted=millis();
  Serial.println("BLE_IDLE:60000ms");
  // Keep GATT responsive while the amplifier is off. Commands do not extend
  // the deadline; a new physical ON resumes playback immediately.
  while(!buttonOn()){
    serviceControl();
    saveSettingsIfPending();
    if(uint32_t(millis()-idleStarted)>=BLE_IDLE_MS){
      if(buttonOn())return;
      sleepNow();
    }
    delay(1);
  }
}
bool validateAudio(){
  if(!audioPartition||AUDIO_BYTES>audioPartition->size)return false;
  uint8_t b[1024];uint32_t crc=0;
  for(uint32_t p=0;p<AUDIO_BYTES;p+=sizeof(b)){
    size_t n=min(uint32_t(sizeof(b)),AUDIO_BYTES-p);
    if(esp_partition_read(audioPartition,p,b,n)!=ESP_OK)return false;
    crc=esp_rom_crc32_le(crc,b,n);
  }
  Serial.printf("AUDIO_CRC:%08lx EXPECT:%08lx\n",(unsigned long)crc,(unsigned long)AUDIO_CRC);
  return crc==AUDIO_CRC;
}
void setup(){
  gpio_deep_sleep_hold_dis();gpio_hold_dis(GPIO_NUM_4);
  pinMode(CTRL,OUTPUT);digitalWrite(CTRL,LOW);
  pinMode(BUTTON,INPUT);pinMode(8,OUTPUT);digitalWrite(8,HIGH);
  Serial.begin(115200);
  bool cold=esp_sleep_get_wakeup_cause()!=ESP_SLEEP_WAKEUP_GPIO;
  if(cold)delay(1500);
  rawOn=stableOn=digitalRead(BUTTON)==LOW;rawChanged=millis();
  audioPartition=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_ANY,"audio");
  Serial.printf("BELL_V5_BLE READY GPIO0=%d FLASH=%u\n",digitalRead(BUTTON),ESP.getFlashChipSize());
  if(!validateAudio()){
    Serial.println("ERROR:AUDIO_INVALID; playback disabled");
    while(true)delay(1000);
  }
  loadBellSettings();volumeGain=int(bellSettings.volume)*256;
  Serial.printf("BLE_START:%s HEAP:%u VOLUME:%u\n",beginBellBle()?"OK":"FAILED",ESP.getFreeHeap(),bellSettings.volume);
  // The idle BLE window in loop also permits USB monitor attachment.
}
uint32_t chooseTrack(){
  if(bellSettings.fixed)return bellSettings.track;
  // The BLE controller supplies hardware entropy while active.
  if(!bleReady)bootloader_random_enable();
  uint32_t choice=esp_random()% (sizeof(TRACKS)/sizeof(TRACKS[0]));
  if(!bleReady)bootloader_random_disable();
  return choice;
}
void loop(){
  serviceControl();
  if(!buttonOn()){waitForSwitchOrSleep();return;}
  uint32_t choice=chooseTrack();
  trackChangeRequested=false;
  setPlaybackState('M',choice);
  Serial.printf("MUSIC:%s\n",TRACKS[choice].name);
  if(!startAudio()){Serial.println("ERROR:I2S");delay(1000);return;}
  while(!audioError){
    bool complete=playSegment(TRACKS[choice].music,true);
    if(audioError)break;
    if(trackChangeRequested && buttonOn()){
      drainToSilence();saveSettingsIfPending();
      choice=chooseTrack();trackChangeRequested=false;
      setPlaybackState('M',choice);Serial.printf("MUSIC:%s\n",TRACKS[choice].name);
      continue;
    }
    if(!complete || !buttonOn())break;
    if(savePending){drainToSilence();saveSettingsIfPending();}
  }
  if(audioError){stopAudio();Serial.println("ERROR:AUDIO_IO");while(true)delay(1000);}
  setPlaybackState('W',choice);
  Serial.printf("WARNING:%s\n",TRACKS[choice].name);
  // Keep CTRL high and I2S running through the faded transition.
  playSegment(TRACKS[choice].warning,false);
  if(!audioError)drainToSilence();
  stopAudio();
  saveSettingsIfPending();
  if(audioError){Serial.println("ERROR:WARNING_IO");while(true)delay(1000);}
  Serial.println("WARNING_DONE");
  // Re-ON during warning is honored after warning completion.
  if(!buttonOn())waitForSwitchOrSleep();
}
