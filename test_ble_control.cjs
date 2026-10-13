const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync(__dirname+'/ble_control.html','utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
const elements={};
function el(id){return elements[id]??=( {value:id==='volume'?'75':id==='track'?'R':'',textContent:'',disabled:false} );}
const uuid=n=>'c3b1000'+n+'-7a6b-4c90-8d12-123456789abc';
const toView=text=>{const data=new TextEncoder().encode(text);return new DataView(data.buffer);};
let current='v75,mR,t0,pM,d0,s0',disconnectHandler,writes=[];
const characteristics={};
for(const n of [1,2,4])characteristics[uuid(n)]={
 listeners:[],async startNotifications(){return this;},
 addEventListener(_,callback){this.listeners.push(callback);},
 async readValue(){return toView(n===2?current:'READY');},
 async writeValueWithResponse(value){writes.push(new TextDecoder().decode(value));},
 emit(text){for(const cb of this.listeners)cb({target:{value:toView(text)}});}
};
const device={addEventListener(_,callback){disconnectHandler=callback;},gatt:{
 connected:false,async connect(){this.connected=true;return this;},
 async getPrimaryService(service){assert.equal(service,'c3b10000-7a6b-4c90-8d12-123456789abc');return {async getCharacteristic(id){assert(characteristics[id]);return characteristics[id];}};},
 disconnect(){this.connected=false;disconnectHandler();}
}};
const context={document:{getElementById:el},navigator:{bluetooth:{async requestDevice(options){assert.equal(options.filters[0].services[0],'c3b10000-7a6b-4c90-8d12-123456789abc');return device;}}},TextDecoder,TextEncoder,console};
vm.createContext(context);vm.runInContext(script,context);
(async()=>{
 assert(el('applyVolume').disabled);
 await el('connect').onclick();assert(!el('applyVolume').disabled);assert.equal(el('volume').value,'75');
 el('volume').value='65';el('volume').oninput();assert.equal(el('volumeValue').textContent,'65%');
 await el('applyVolume').onclick();assert.equal(writes.at(-1),'V65');
 el('track').value='T3';await el('applyTrack').onclick();assert.equal(writes.at(-1),'T3');
 current='v65,mT,t1,pW,d1,s3';characteristics[uuid(2)].emit(current);
 assert.equal(el('track').value,'T3');assert(el('state').textContent.includes('警示音播放'));assert(el('state').textContent.includes('设置未保存'));
 await el('save').onclick();assert.equal(writes.at(-1),'S');
 characteristics[uuid(4)].emit('WAIT:S');assert(el('message').textContent.includes('等待'));
 characteristics[uuid(4)].emit('OK:S');assert(el('message').textContent.includes('保存成功'));
 el('track').value='R';await el('applyTrack').onclick();assert.equal(writes.at(-1),'R');
 el('disconnect').onclick();assert(el('applyVolume').disabled);assert(!el('connect').disabled);
 delete context.navigator.bluetooth;await el('connect').onclick();assert(el('message').textContent.includes('不支持'));
 console.log('BLE control UI: connect, volume, pending selection, save ACK, random, disconnect, unsupported browser PASS');
})().catch(error=>{console.error(error);process.exitCode=1;});
