# Callback Tracker sync server

Relays "customer was reached" events between the shop phones so a callback
answered on one phone disappears from the others. Phones keep working on
their own when this server is off; they catch up when it's back.

It listens only on this PC's Tailscale address, so only devices signed in to
your tailnet can reach it. Events older than 7 days are deleted automatically.

## Install (Linux PC)

1. Install Tailscale and sign in: `tailscale ip -4` must print an address.
2. From this repo's root:

   ```sh
   sudo install -Dm644 server/callback_sync_server.py /opt/callback-sync/callback_sync_server.py
   sudo install -Dm644 server/callback-sync.service /etc/systemd/system/callback-sync.service
   sudo systemctl daemon-reload
   sudo systemctl enable --now callback-sync
   ```

3. Check it: `curl "http://$(tailscale ip -4):8787/health"` prints `{"serverId": "..."}`.
4. Note the PC's Tailscale name (first column of `tailscale status` for this PC).

To update after a code change, repeat the first `install` line and run
`sudo systemctl restart callback-sync`. Logs: `journalctl -u callback-sync`.

## Connect each phone

1. Install the Tailscale app and sign in to the same account.
2. In Callback Tracker, open **Settings**, enter `http://<pc-tailscale-name>:8787`,
   tap **Test connection** (it should say "Connected ✓"), then **Save**.

## Check it works (on the real phones)

1. Miss a call on phone A from a test number. Call that number back from phone B
   and talk for a few seconds. Within about 30 s it leaves A's Pending list, and
   History says "answered on another phone".
2. Miss calls on A and B from the same number. Tap **Mark resolved** on A. It leaves
   B's Pending list too ("marked resolved on another phone").
3. Turn off Wi-Fi and mobile data on A, then miss a call on it. It still appears in
   Pending on A.
4. Turn A's network back on. Settings on A shows "0 waiting to upload" shortly after.
5. Stop the server (`sudo systemctl stop callback-sync`) and make a call back from B.
   Start it again, and within about 30 s the callback clears on A.

## Tests

`python3 -m unittest discover -s server -v`
