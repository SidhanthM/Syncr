#!/usr/bin/env python3
# A stand-in for `pi --mode rpc` that replays a recorded answer with recorded timing.
# Config: JSON file named by FAKE_PI_CONFIG. Wall-clock events go to config["log"], one JSON line each.
import json, os, sys, time

def load(): return json.load(open(os.environ["FAKE_PI_CONFIG"]))
cfg = load()
born = time.time()
first_command = True

def log(**v):
    global cfg
    with open(cfg["log"], "a") as f: f.write(json.dumps({"t": time.time(), **v}) + "\n")

def emit(value):
    sys.stdout.write(json.dumps(value, separators=(",", ":")) + "\n"); sys.stdout.flush()

def chunks(text, n):
    return [text[i:i + n] for i in range(0, len(text), n)]

log(ev="spawn")
for line in sys.stdin.buffer:
    cmd = json.loads(line); kind = cmd["type"]
    cfg = load()   # the harness rewrites the config between asks
    if first_command:
        first_command = False
        time.sleep(max(0.0, cfg["coldSeconds"] - (time.time() - born)))   # pi's own boot, paid before its first answer
        log(ev="booted")
    if kind == "new_session":
        if cfg["sessionSeconds"] > 0: time.sleep(cfg["sessionSeconds"])
        log(ev="session")
    if kind == "prompt":
        gate = "quiet reading gate" in cmd["message"]
        log(ev="prompt_rx", gate=gate)
        emit({"type": "response", "id": cmd.get("id"), "success": True, "data": {"disposition": "started"}})
        text = cfg["gateText"] if gate else cfg["response"]
        time.sleep(cfg["gateSeconds"] if gate else cfg["firstTokenSeconds"])
        step = cfg["chunkChars"] / cfg["charsPerSecond"] * cfg["providerScale"]
        for i, part in enumerate(chunks(text, cfg["chunkChars"])):
            if i: time.sleep(step)
            log(ev="chunk", i=i, gate=gate)
            emit({"type": "message_update", "assistantMessageEvent": {"type": "text_delta", "delta": part}})
        emit({"type": "message_end", "message": {"role": "assistant", "stopReason": "stop", "usage": {"input": 800, "output": 40, "cacheRead": 0, "cacheWrite": 0}}})
        emit({"type": "agent_settled"})
        log(ev="settled")
    else:
        emit({"type": "response", "id": cmd.get("id"), "command": kind, "success": True, "data": {"cancelled": False}})
