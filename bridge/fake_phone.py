#!/usr/bin/env python3
"""Plays the phone for tests and demos: long-polls the server and answers asks with a rule.
Usage: fake_phone.py URL TOKEN [choice]   (choice = the option text to pick; default: the first option)"""
import json
import sys
import urllib.request


def call(url, token, path, body=None, timeout=70):
    req = urllib.request.Request(url + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def run(url, token, pick=None, stop=lambda: False, seen=None, wait=2):
    """Polls until stop() is true. pick(item) -> choice or None (None = do not answer). seen gets every item."""
    since = 0
    while not stop():
        r = call(url, token, "/events?since=%d&wait=%d" % (since, wait))
        since = r["seq"]
        for item in r["items"]:
            if seen is not None:
                seen.append(item)
            if item["options"]:
                choice = pick(item) if pick else item["options"][0]
                if choice is not None:
                    call(url, token, "/answer", {"id": item["id"], "choice": choice})


if __name__ == "__main__":
    url, token = sys.argv[1], sys.argv[2]
    want = sys.argv[3] if len(sys.argv) > 3 else None
    run(url, token, lambda i: want if want in i["options"] else i["options"][0], seen=None)
