# Networking

Party multiplayer runs through the game client and its host screen. Tanks Online
is a separate service for online screens and shared levels. Both use network
events, but their startup and persistent data differ.

## Party connections and events

Start with [ScreenPartyHost](../src/main/java/tanks/gui/screen/ScreenPartyHost.java),
[ScreenPartyLobby](../src/main/java/tanks/gui/screen/ScreenPartyLobby.java), and
the [network package](../src/main/java/tanks/network). `ClientHandler` and
`ServerHandler` manage connections; `SteamNetworkHandler` handles Steam
integration.

To check a party locally:

1. Launch two copies of the same JAR with `debug no_steam`, each using a
   separate, existing `userdir`. Follow the [launch instructions](getting-started.md),
   including the macOS JVM options if applicable.
2. In each copy, choose Play, then Multiplayer, and supply a username if asked.
   Set the same party port in both copies, for example 8080.
3. In the first copy, choose Create a party. In the second, choose Join a party,
   enter `127.0.0.1` as the address, and choose Join.
4. Confirm both players appear, then have the host select Random co-op or a
   saved level under My levels. Exercise the changed behavior in both windows.

For a client on another machine on the same network, use the host's LAN IP
address instead of `127.0.0.1` and allow the party port through its firewall.

[Game.registerEvents()](../src/main/java/tanks/Game.java) registers event classes
with [NetworkEventMap](../src/main/java/tanks/network/NetworkEventMap.java).
IDs are assigned sequentially, so peers need matching registration order.
The client handshake in
[EventSendClientDetails](../src/main/java/tanks/network/event/EventSendClientDetails.java)
checks `Game.network_protocol`.

[MessageReader](../src/main/java/tanks/network/MessageReader.java) deserializes
incoming messages. Ordinary gameplay events enter `Game.eventsIn` and are
executed during `Panel.update()`. Game logic queues outgoing events in
`Game.eventsOut`; party dispatch passes them to connection handlers. Some
connection events have immediate handler paths, so inspect the reader when
changing connection behavior.

## Event serialization and authority

[INetworkEvent](../src/main/java/tanks/network/event/INetworkEvent.java) defines
`read`, `write`, and `execute`. Its default serializer uses
[ReflectionHandle](../src/main/java/tanks/network/ReflectionHandle.java) to
visit non-static fields, including superclass fields. `@NetworkIgnored`
excludes a field. Events can override the default reader and writer.

The default `double` handler writes a 32-bit float and converts it back on
read. Do not assume that a Java `double` field retains double precision on
the wire. Changing field types, fields, or their serialization can change the
protocol even when `execute()` is unchanged.

[PersonalEvent](../src/main/java/tanks/network/event/PersonalEvent.java) carries
a `clientID`. `MessageReader` sets it from the connection when decoding these
events. Event implementations use it to determine which sender may cause a
state change. For example,
[EventTankUpdate](../src/main/java/tanks/network/event/EventTankUpdate.java)
applies updates only when `clientID == null`. Preserve sender checks when
changing an event.

On the client, messages received from the host are decoded with a null sender
ID. On the host, messages received from a client carry that client's ID.
For `EventTankUpdate`, the null check therefore accepts host-supplied state
and rejects a client attempting to send that same update to the host. Local
code can also enqueue events with a null ID; the value is a dispatch convention,
not an authentication mechanism by itself.

When adding an event:

1. Provide a public no-argument constructor for deserialization.
2. Register it consistently on both peers.
3. Match reader and writer field order and types, or use the default serializer
   with supported field types.
4. Validate sender authority in the execution path.
5. Review protocol compatibility and test with matching host/client builds.

Debug registration checks the no-argument constructor and attempts a
serialization round trip. This is a diagnostic check, not a substitute for
testing an event with populated fields and a live connection.

## Tanks Online service

The launcher argument `online_server` selects
[TanksOnlineServer](../src/main/java/tanksonline/TanksOnlineServer.java) and
skips creation of the game window. Replace the JAR filename with the actual
built artifact:

```sh
java -jar "build/libs/Tanks-<version>-<commit>.jar" online_server port=8080
```

Startup registers events, loads `PlayerMap`, starts `CommandExecutor`, and runs
the server. This command starts the level sharing service; use the in-game
party host flow to host combat sessions.

[PlayerMap](../src/main/java/tanksonline/PlayerMap.java) stores `usernames.tanks`,
`levels/`, and `accesscodes/` relative to the process working directory. Choose
that directory deliberately. The desktop `userdir` setting does not relocate
these server files. Server commands are implemented in
[CommandExecutor](../src/main/java/tanksonline/CommandExecutor.java).
