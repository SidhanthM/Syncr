#!/usr/bin/env python3
import json, sys, time
prompt = sys.stdin.read()
assert '--json' in sys.argv
assert 'web_search="live"' in sys.argv
text = '{"type":"margin","quote":"cache","kind":"explanation","text":"A source-backed cache explanation."}\n'
def emit(r): print(json.dumps(r), flush=True)
emit({'type':'item.updated','item':{'type':'agent_message','text':text[:40]}})
if 'SIMULATE_SLOW' in prompt: time.sleep(30)
emit({'type':'item.completed','item':{'type':'agent_message','text':text}})
emit({'type':'turn.completed','usage':{'input_tokens':20,'cached_input_tokens':3,'output_tokens':5}})
