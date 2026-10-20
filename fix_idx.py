import re
p = r'osint\src\main\java\io\notrack\osint\MainActivity.java'
s = open(p, encoding='utf-8').read()
old = "h.append(\"<div class='card'><div class='t'>\").append(escapeHtml(r[0])).append(\"</div><a href='\").append(escapeHtml(r[1])).append(\"' target='_blank'>\").append(escapeHtml(r[1])).append(\"</a><div class='s'>\").append(escapeHtml(r[2] == null ? \"\" : r[2])).append(\"</div></div>\");"
new = "h.append(\"<div class='card'><div class='t'>\").append(escapeHtml(r[1])).append(\"</div><a href='\").append(escapeHtml(r[2])).append(\"' target='_blank'>\").append(escapeHtml(r[2])).append(\"</a><div class='s'>\").append(escapeHtml(r[3] == null ? \"\" : r[3])).append(\"</div></div>\");"
print('found:', old in s)
s = s.replace(old, new)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('done')