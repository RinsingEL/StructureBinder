import io, re, sys

path = sys.argv[1]
text = io.open(path, encoding="utf-8").read()
pattern = re.compile(r"<<<<<<< [^\n]*\n(.*?)=======\n(.*?)>>>>>>> [^\n]*\n", re.S)


def union(match):
    ours, theirs = match.group(1), match.group(2)
    if ours and not ours.endswith("\n"):
        ours += "\n"
    return ours + theirs


resolved, count = pattern.subn(union, text)
io.open(path, "w", encoding="utf-8", newline="").write(resolved)
print("resolved", count)
