import serial
import time
import pathlib
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--port', default='COM11')
parser.add_argument('--seconds', type=float, default=60)
args=parser.parse_args()
end=time.monotonic()+args.seconds
log=pathlib.Path(__file__).resolve().parent/'generated/serial.log'
with log.open('a',encoding='utf-8') as f:
    while time.monotonic()<end:
        try:
            port=serial.Serial(port=None,baudrate=115200,timeout=.25)
            port.dtr=False
            port.rts=False
            port.port=args.port
            port.open()
            with port as s:
                while time.monotonic()<end:
                    line=s.readline().decode('utf-8',errors='replace')
                    if line:
                        print(line,end='',flush=True);f.write(line);f.flush()
        except serial.SerialException:
            time.sleep(.5)
