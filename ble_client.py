"""Nearby BLE command-line control and on-device GATT regression checks."""
import argparse
import asyncio
import json
import pathlib
import re
from bleak import BleakClient, BleakScanner

SERVICE='c3b10000-7a6b-4c90-8d12-123456789abc'
COMMAND='c3b10001-7a6b-4c90-8d12-123456789abc'
STATE='c3b10002-7a6b-4c90-8d12-123456789abc'
TRACKS='c3b10003-7a6b-4c90-8d12-123456789abc'
ACK='c3b10004-7a6b-4c90-8d12-123456789abc'

def parse_state(text):
    match=re.fullmatch(r'v(\d+),m([RT]),t(-?\d+),p([IMWE]),d([01]),s([0-9])',text)
    if not match:
        raise ValueError(f'Unexpected state: {text}')
    return dict(volume=int(match[1]),mode=match[2],track=int(match[3]),phase=match[4],dirty=bool(int(match[5])),selected_track=int(match[6]))

async def main(args):
    if args.address:
        device=await BleakScanner.find_device_by_address(args.address,timeout=args.timeout)
    else:
        device=await BleakScanner.find_device_by_filter(
            lambda d,a: SERVICE in a.service_uuids or a.local_name=='DepartureBell',timeout=args.timeout)
    if not device:
        raise RuntimeError('DepartureBell not found. Keep the physical switch ON and press RESET if necessary.')
    print('FOUND',device.address,device.name,flush=True)
    async with BleakClient(device,timeout=20) as client:
        received=[]
        def notification(kind):
            def callback(_,value):
                text=bytes(value).decode();received.append((kind,text));print(kind,text,flush=True)
            return callback
        await client.start_notify(STATE,notification('STATE'))
        await client.start_notify(ACK,notification('ACK'))
        async def state():return parse_state((await client.read_gatt_char(STATE)).decode())
        async def send(command,expected=None):
            received.clear()
            await client.write_gatt_char(COMMAND,command.encode(),response=True)
            end=asyncio.get_running_loop().time()+3
            while asyncio.get_running_loop().time()<end:
                acks=[v for k,v in received if k=='ACK']
                if acks:
                    if expected:assert expected in acks,(command,acks)
                    # ACK precedes the state update on-device. Wait for that
                    # update before reading so tests cannot observe the old value.
                    ack_position=max(i for i,(k,_) in enumerate(received) if k=='ACK')
                    if any(k=='STATE' for k,_ in received[ack_position+1:]):return
                await asyncio.sleep(.05)
            raise AssertionError(f'No acknowledgement/state update for {command}')
        original=await state()
        print('INITIAL',original,flush=True)
        tracks_text=(await client.read_gatt_char(TRACKS)).decode()
        track_ids=[int(item.split('=',1)[0]) for item in tracks_text.split(';')]
        assert track_ids==list(range(len(track_ids))),tracks_text
        print('TRACKS',tracks_text,flush=True)
        if args.test:
            report={'address':device.address,'initial':original,'checks':[]}
            try:
                for command,expected in [('V101','ERR:command'),('V-1','ERR:command'),('T'+str(len(track_ids)),'ERR:command'),('V75x','ERR:command')]:
                    await send(command,expected);assert (await state())['volume']==original['volume']
                    report['checks'].append(command+': rejected')
                for volume in [0,65,75]:
                    await send(f'V{volume}',f'OK:V{volume}');assert (await state())['volume']==volume
                    report['checks'].append(f'volume {volume}: PASS')
                for track in track_ids:
                    await send(f'T{track}',f'OK:T{track}')
                    end=asyncio.get_running_loop().time()+3
                    while True:
                        current=await state()
                        if current['mode']=='T' and current['track']==track:break
                        assert asyncio.get_running_loop().time()<end,current
                        await asyncio.sleep(.15)
                    report['checks'].append(f'track {track}: PASS')
                await send('R','OK:R');assert (await state())['mode']=='R'
                report['checks'].append('random: PASS')
            finally:
                await send('V'+str(original['volume']))
                await send('T'+str(original['selected_track']))
                if original['mode']=='R':await send('R')
            report['restored']=await state()
            pathlib.Path(__file__).with_name('generated').joinpath('ble_verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
            print('BLE GATT tests PASS; original settings restored',flush=True)
        for command in args.commands:
            await send(command)
        if args.wait:
            await asyncio.sleep(args.wait)
        if client.is_connected:print('FINAL',await state(),flush=True)
        else:print('DISCONNECTED (physical OFF/deep sleep also disconnects BLE)',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('commands',nargs='*',help='V0..V100, T0..T5, R, S, ?')
    parser.add_argument('--address')
    parser.add_argument('--timeout',type=float,default=15)
    parser.add_argument('--wait',type=float,default=0)
    parser.add_argument('--test',action='store_true')
    asyncio.run(main(parser.parse_args()))
