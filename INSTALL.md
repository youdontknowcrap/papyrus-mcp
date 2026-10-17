# Install Papyrus MCP (beginner steps)

You need one file: `org.papyrus.mcp_0.1.0.<date>.jar`. It is in `org.papyrus.mcp/dist/` in this project.

## A. Put the plugin into Papyrus (no git needed)
1. Close Papyrus.
2. Find your Papyrus folder. It is the folder that contains `papyrus-desktop` (on macOS:
   `Papyrus.app/Contents/Eclipse`). Inside it, make a folder called `dropins` if it isn't there.
3. Copy the jar into `dropins`.
   - Linux/macOS terminal (change the paths to yours):
     ```
     mkdir -p ~/Papyrus/dropins
     cp ~/Downloads/org.papyrus.mcp_0.1.0.*.jar ~/Papyrus/dropins/
     ```
   - Windows: drag the jar into `C:\...\Papyrus\dropins\` in Explorer.
4. Start Papyrus and open your model (.di).
5. Check that it works:
   ```
   curl -s -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ping"}}' http://127.0.0.1:7341/mcp
   ```
   You should see `"pong":true` and `"modelOpen":true`.
   If you get no answer, start Papyrus once with `-clean` (add it as the first line of
   `papyrus-desktop.ini`, start, then remove it again).
6. Optional: go to Window > Preferences > Papyrus MCP to set a port or token. Restart Papyrus
   after changing them.

## A2. Alternative: test without touching the Papyrus install (Linux)
```
bash /home/rambo/SYSML/papyrus-mcp/test/laptop-launch.sh     # starts Papyrus with the plugin
# open your model in Papyrus, then in a second terminal:
bash /home/rambo/SYSML/papyrus-mcp/test/laptop-smoke.sh      # ping + list_packages
```
Both scripts take `PAPYRUS=...`, `JARDIR=...`, `PORT=...`, `TOKEN=...` if your paths differ.

## B. Connect your AI client
- Clients that support HTTP MCP: use the server URL `http://127.0.0.1:7341/mcp`. If you set a
  token, also add the header `Authorization: Bearer <token>`.
- Clients that only support stdio (for example, a Claude Desktop config):
  ```json
  { "mcpServers": { "papyrus": { "command": "npx", "args": ["mcp-remote", "http://127.0.0.1:7341/mcp"] } } }
  ```


## B2. Let the server guide your AI (ICONIX + agile workflow)

The server also offers **prompts** and **resources**. They are ready-made instructions and
checklists that make an AI follow the ICONIX modeling process and an agile backlog. Here is how to use them:

1. In your AI client, look for the server's prompts. Claude Desktop shows them under the "+" /
   attachment menu; in Claude Code type `/` and look for `papyrus-mcp`. Pick one, for example
   `iconix_robustness`, and fill in the use case name.
2. Or simply ask: *"Read workflow://iconix, then call workflow_status and tell me what to do next."*
3. To use the Boundary / Control / Entity stereotypes in your model, ask the AI to run
   `apply_profile` with profile `ICONIX`. The profile ships inside the plugin.

`workflow_status` never changes your model. It only reports what is done and what is missing.

## C. Build it yourself from source (optional, needs Java 21 JDK)
```
cd papyrus-mcp/org.papyrus.mcp
PAPYRUS=/path/to/Papyrus ./build.sh
```
The new jar appears in `dist/`. Delete the old jar from `dropins` before you copy the new one in.

## D. Git basics for this project
The repo lives on the box at `/workspace/papyrus-mcp`. It has not been pushed anywhere yet.
To put it on GitHub:
1. On github.com, click **New repository**, name it `papyrus-mcp`, and leave it empty (no README).
2. On the box, run:
   ```
   cd /workspace/papyrus-mcp
   git remote add origin https://github.com/youdontknowcrap/papyrus-mcp.git
   git push -u origin main
   ```
3. Later, on your own computer:
   ```
   git clone https://github.com/youdontknowcrap/papyrus-mcp.git
   ```
Day-to-day commands:
- `git status` shows what changed.
- `git add -A` then `git commit -m "message"` saves a snapshot.
- `git push` uploads it.
