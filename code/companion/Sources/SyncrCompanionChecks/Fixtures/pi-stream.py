#!/usr/bin/env python3
import json, os, sys, time

def emit(value):
    print(json.dumps(value, separators=(',', ':')), flush=True)

history = []
for line in sys.stdin.buffer:
    cmd = json.loads(line)
    kind = cmd['type']
    if kind == 'new_session':
        history = []
    if kind == 'prompt':
        assert not history, 'history must reset before each pass'
        history.append(cmd['message'])
        emit({'type':'response','id':cmd.get('id'),'success':True,'data':{'disposition':'started'}})
        text = '{"type":"margin","quote":"cache","kind":"question","text":"When is it invalidated?"}\n'
        for part in (text[:18], text[18:]):
            emit({'type':'message_update','assistantMessageEvent':{'type':'text_delta','delta':part}})
            time.sleep(.005)
        if 'SIMULATE_DIE' in cmd['message']:
            emit({'type':'message_update','assistantMessageEvent':{'type':'text_delta','delta':'{"type":"margin"'}})
            os._exit(3)
        if 'SIMULATE_SLOW' in cmd['message']:
            emit({'type':'message_update','assistantMessageEvent':{'type':'text_delta','delta':'{"type":"margin"'}})
            time.sleep(30)
        emit({'type':'message_end','message':{'role':'assistant','stopReason':'stop','usage':{'input':os.getpid(),'output':5,'cacheRead':2,'cacheWrite':0}}})
        emit({'type':'agent_settled'})
    else:
        emit({'type':'response','id':cmd.get('id'),'command':kind,'success':True,'data':{'cancelled':False}})
