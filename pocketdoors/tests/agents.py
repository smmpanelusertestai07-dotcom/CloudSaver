#!/usr/bin/env python3
"""Reads the agent catalog as data, so a gate can ask about a named field.

This exists because a gate written as a grep passed on the wrong thing, for the fourth time in
this project: it looked for an empty string inside one entry to prove that entry claimed nothing,
and the entry had two empty strings in a row. Filling one in left the other there, and the check
stayed green while the app claimed a maker published something they do not.

So the fields are read by position, the way the constructor reads them. The count is checked
too: an Agent that grows a field and a gate that does not know about it should stop the build,
not quietly start answering about the wrong one.
"""
import re
import sys

FIELDS = ["id", "name", "start", "extension", "cost", "free", "limit", "proven"]


def split_args(text):
    """Splits one argument list on its top-level commas, respecting Java strings."""
    args, depth, current, in_string, escaped = [], 0, [], False, False
    for character in text:
        if in_string:
            current.append(character)
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
            current.append(character)
        elif character in "([":
            depth += 1
            current.append(character)
        elif character in ")]":
            depth -= 1
            current.append(character)
        elif character == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    if current:
        args.append("".join(current).strip())
    return args


def value(expression):
    """A Java string expression as its text; anything else verbatim."""
    pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', expression)
    if not pieces:
        return expression.strip()
    return "".join(piece.encode().decode("unicode_escape") for piece in pieces)


def agents(path):
    source = open(path, encoding="utf-8").read()
    found = []
    for match in re.finditer(r"new Agent\(", source):
        start = match.end()
        depth, index = 1, start
        while depth:
            if source[index] == '"':
                index += 1
                while source[index] != '"':
                    index += 2 if source[index] == "\\" else 1
            elif source[index] == "(":
                depth += 1
            elif source[index] == ")":
                depth -= 1
            index += 1
        args = split_args(source[start:index - 1])
        if len(args) != len(FIELDS):
            raise SystemExit("an Agent has %d fields, not %d" % (len(args), len(FIELDS)))
        found.append({name: value(arg) for name, arg in zip(FIELDS, args)})
    return found


if __name__ == "__main__":
    path, agent_id, field = sys.argv[1], sys.argv[2], sys.argv[3]
    for agent in agents(path):
        if agent["id"] == agent_id:
            print(agent[field])
            break
    else:
        raise SystemExit("no agent called " + agent_id)
