#!/usr/bin/python3
"""Recorded RPC child: no network, credentials, tools or filesystem access."""
import json
import sys

for line in sys.stdin.buffer:
    command = json.loads(line)
    if command['type'] == 'get_available_models':
        print(json.dumps({'type': 'response', 'id': command['id'], 'success': True, 'data': {'models': [
            {'provider': 'openai-codex', 'id': 'gpt-6.1-sol', 'name': 'Recorded Sol'},
            {'provider': 'unrelated', 'id': 'hidden', 'name': 'Not Codex'}]}}), flush=True)
    elif command['type'] == 'prompt':
        print(json.dumps({'type': 'response', 'id': command['id'], 'success': True, 'data': {'disposition': 'started'}}), flush=True)
        print(json.dumps({'type': 'message_end', 'message': {'role': 'assistant', 'stopReason': 'stop', 'content': [
            {'type': 'text', 'text': '{"calls":[{"name":"stay_quiet","arguments":{}}]}'}],
            'usage': {'input': 20, 'output': 10, 'cacheRead': 4, 'cacheWrite': 0}}}), flush=True)
        print(json.dumps({'type': 'agent_end'}), flush=True)
        print(json.dumps({'type': 'agent_settled', 'aborted': False}), flush=True)
    else:
        print(json.dumps({'type': 'response', 'id': command['id'], 'success': True}), flush=True)
