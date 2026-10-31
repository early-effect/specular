# Specular

Edit Specular pages in VS Code and Cursor.

A documentation page is a Scala `DocSpec`. This extension keeps you in that file: markdown highlighting and completions inside `md"""`, a split preview of the running site, and shift-click from a preview link to the same heading in Scala.

## Requirements

- The [Scala extension](https://marketplace.visualstudio.com/items?itemName=scala-lang.scala) (Metals).
- A Specular site served by `sbt docs/specularPreview` (default `http://localhost:8765`).

## Use

Open a Scala file that defines a page. **Open Preview** is in the editor title bar. Save reloads the preview after a clean compile. Shift-click a link in the preview to open that page in the preview and in the Scala file.

Set `specular.previewCommand` and `specular.previewOrigin` if the preview task or port is different.

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)
