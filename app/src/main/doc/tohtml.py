import sys
import markdown

header="""
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>OpenLiveStacker - Licenses</title>
</head>
<body>
"""

footer="""
</body>
</html>
"""

def make_manual():
    with open("source.md", "r",encoding="utf-8") as input_file:
        text = input_file.read()
        md  = markdown.Markdown()
        html = md.convert(text)

    with open("../assets/copying.html", "w",encoding="utf-8") as output_file:
        output_file.write(header)
        output_file.write(html)
        output_file.write(footer)

if __name__ == "__main__":
    make_manual()
