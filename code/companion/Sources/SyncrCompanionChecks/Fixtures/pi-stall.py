#!/usr/bin/env python3
import json, sys, time
for line in sys.stdin.buffer:
    cmd = json.loads(line)
    print(json.dumps({'type':'response','id':cmd.get('id'),'command':cmd['type'],'success':True,'data':{'cancelled':False}}), flush=True)
    if cmd['type'] == 'set_thinking_level':
        # Deliberately stop reading stdin before a large prompt is written.
        time.sleep(30)
