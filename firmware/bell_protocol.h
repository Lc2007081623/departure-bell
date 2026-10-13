#pragma once
#include <stddef.h>
#include <stdint.h>

enum class BellCommandType : uint8_t { Invalid, Volume, Track, Random, Save, Status };
struct BellCommand { BellCommandType type; uint8_t value; };
constexpr bool bellSpace(char c){return c==' ' || c=='\r' || c=='\n' || c=='\t';}
constexpr BellCommand parseBellCommand(const char *s,size_t n){
  if(!s || n==0 || n>12)return {BellCommandType::Invalid,0};
  while(n && bellSpace(*s)){s++;n--;}
  while(n && bellSpace(s[n-1]))n--;
  if(n==1){
    if(s[0]=='R')return {BellCommandType::Random,0};
    if(s[0]=='S')return {BellCommandType::Save,0};
    if(s[0]=='?')return {BellCommandType::Status,0};
  }
  if(n<2 || (s[0]!='V' && s[0]!='T'))return {BellCommandType::Invalid,0};
  unsigned value=0;
  for(size_t i=1;i<n;i++){
    if(s[i]<'0' || s[i]>'9')return {BellCommandType::Invalid,0};
    value=value*10+unsigned(s[i]-'0');
    if(value>100)return {BellCommandType::Invalid,0};
  }
  if(s[0]=='T' && value>3)return {BellCommandType::Invalid,0};
  return {s[0]=='V'?BellCommandType::Volume:BellCommandType::Track,uint8_t(value)};
}
// Boundary and malformed-input regressions are evaluated by the real compiler.
static_assert(parseBellCommand("V0",2).type==BellCommandType::Volume,"mute command");
static_assert(parseBellCommand("V100",4).value==100,"full volume");
static_assert(parseBellCommand("V101",4).type==BellCommandType::Invalid,"volume range");
static_assert(parseBellCommand("V-1",3).type==BellCommandType::Invalid,"negative volume");
static_assert(parseBellCommand("V75x",4).type==BellCommandType::Invalid,"trailing garbage");
static_assert(parseBellCommand("T3",2).value==3,"last track");
static_assert(parseBellCommand("T4",2).type==BellCommandType::Invalid,"track range");
static_assert(parseBellCommand(" R\n",3).type==BellCommandType::Random,"trim command");
static_assert(parseBellCommand("V",1).type==BellCommandType::Invalid,"missing value");
static_assert(parseBellCommand("",0).type==BellCommandType::Invalid,"empty command");
static_assert(parseBellCommand("V99999999999",12).type==BellCommandType::Invalid,"oversized value");

struct BellSettings { uint8_t volume=75,track=0; bool fixed=false; };
constexpr uint32_t packBellSettings(BellSettings s){
  return 0xB1000000u | s.volume | uint32_t(s.track)<<8 | uint32_t(s.fixed)<<16;
}
constexpr bool validBellSettings(uint32_t v){
  return (v&0xfffe0000u)==0xB1000000u && (v&255)<=100 && ((v>>8)&255)<4;
}
constexpr BellSettings unpackBellSettings(uint32_t v){
  return {uint8_t(v&255),uint8_t((v>>8)&255),bool((v>>16)&1)};
}
static_assert(validBellSettings(packBellSettings({0,3,true})),"settings roundtrip bounds");
static_assert(!validBellSettings(0xffffffff),"reject erased settings");
