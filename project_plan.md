# Java NIO TCP Chat Application --- Technical Plan

## 1. Project Overview

The project is a messaging application built on Java TCP sockets. It
should support:

-   One-to-one conversations.
-   Group conversations.
-   Text messages.
-   File, image, and document sharing.
-   Voice notes (recorded audio files).
-   Multiple simultaneous clients.
-   A desktop GUI.
-   Reliable file transfers, including progress reporting and eventual
    resume support.

The goal is to combine three objectives:

1.  Learn networking, protocols, concurrency, and data transfer.
2.  Build a performant and maintainable application.
3.  Deliver a complete application with a graphical interface.

The recommended approach is to build incrementally. Start with reliable
communication and protocol framing, then add file transfer, persistence,
security, and advanced optimizations.

------------------------------------------------------------------------

## 2. Recommended Technology Stack

  -----------------------------------------------------------------------
  Component               Recommended technology  Purpose
  ----------------------- ----------------------- -----------------------
  Language                Java 21 or newer LTS    Application
                                                  implementation

  Networking              Java NIO                Non-blocking TCP
                                                  communication

  Server socket           `ServerSocketChannel`   Accept incoming
                                                  connections

  Client/server           `SocketChannel`         Read and write TCP data
  connection                                      

  I/O multiplexing        `Selector`              Monitor multiple
                                                  channels

  Concurrency             `ExecutorService`,      Run bounded background
                          `ThreadPoolExecutor`    work

  Protocol                Custom binary framing   Define application
                                                  messages

  Serialization           JSON initially;         Encode structured
                          Protocol Buffers as a   control data
                          later option            

  Compression             Zstandard (Zstd)        Compress suitable
                                                  payloads

  Security                TLS, `SSLContext`,      Protect transport
                          potentially `SSLEngine` 

  GUI                     JavaFX                  Desktop interface

  Persistence             SQLite initially        Store users,
                                                  conversations, and
                                                  message metadata

  Build                   Maven                   Dependency and build
                                                  management

  Testing                 JUnit                   Unit and integration
                                                  tests

  Network inspection      Wireshark               Inspect traffic and
                                                  diagnose network
                                                  behaviour
  -----------------------------------------------------------------------

### Why Java NIO?

Java NIO supports non-blocking I/O and I/O multiplexing. A selector can
monitor many channels, allowing a small number of I/O threads to manage
many connections.

Non-blocking I/O is not automatically multithreaded. It changes how the
application waits for and processes I/O readiness events. Worker threads
can be used for operations that should not block the event loop.

------------------------------------------------------------------------

## 3. High-Level Architecture

Separate the application into four logical layers.

### 3.1 Presentation layer

Responsible for:

-   JavaFX windows and views.
-   Conversation list and message display.
-   File selection and attachment previews.
-   Transfer progress.
-   Notifications and connection status.
-   User settings.

The GUI should not know about TCP packet framing or socket management.

### 3.2 Application layer

Responsible for application behaviour:

-   Authentication.
-   Private messaging.
-   Group management.
-   Message routing.
-   File-transfer lifecycle.
-   Delivery receipts.
-   User presence.

This layer should expose operations such as `sendMessage()` rather than
requiring the GUI to construct network packets.

### 3.3 Protocol layer

Responsible for:

-   Packet encoding and decoding.
-   Message framing.
-   Serialization.
-   Protocol versioning.
-   Compression flags.
-   Validation of packet metadata.

It translates application messages into a format that can be transmitted
over TCP and reconstructs them on receipt.

### 3.4 Transport layer

Responsible for:

-   `ServerSocketChannel` and `SocketChannel`.
-   `Selector` and `SelectionKey`.
-   Accepting, reading, and writing connections.
-   Per-connection buffers and outgoing queues.
-   Connection lifecycle and cleanup.

The transport layer should not need to understand the meaning of a chat
message.

------------------------------------------------------------------------

## 4. Java NIO Server Design

The server can begin with one selector thread and bounded worker pools.

### 4.1 Main NIO components

  -----------------------------------------------------------------------
  Class                               Responsibility
  ----------------------------------- -----------------------------------
  `ServerSocketChannel`               Accept incoming TCP connections

  `SocketChannel`                     Communicate with a connected client

  `Selector`                          Monitor registered channels for I/O
                                      readiness

  `SelectionKey`                      Represent a channel's registered
                                      interest and readiness operations

  `ByteBuffer`                        Hold data being read or written
  -----------------------------------------------------------------------

### 4.2 Event-loop workflow

The selector loop generally:

1.  Waits for registered channels to become ready.
2.  Accepts new connections when the server channel is acceptable.
3.  Reads available bytes when a client channel is readable.
4.  Decodes complete application frames.
5.  Dispatches application work.
6.  Writes queued outgoing bytes when a channel is writable.
7.  Cleans up closed or failed connections.

### 4.3 Important NIO details

**Partial reads:** A read operation may return only part of a packet.
Keep an input buffer for each connection and preserve incomplete data
until more bytes arrive.

**Partial writes:** A write operation may write fewer bytes than the
buffer contains. Keep the remaining bytes in an outgoing queue and
resume writing when the channel becomes writable.

**Message boundaries:** TCP is a byte stream. One `read()` does not
necessarily correspond to one application message.

**Backpressure:** If a client receives data slowly, its outgoing queue
can grow. Bound queue sizes and define what happens when a client cannot
keep up.

**Event-loop responsiveness:** Do not perform blocking disk operations,
lengthy compression, or expensive processing on the selector thread.
Delegate such work to bounded worker pools.

**Interest operations:** Register or update `OP_READ` and `OP_WRITE`
appropriately. Avoid permanently selecting write readiness when there is
nothing to send, since channels are often writable most of the time.

### 4.4 Initial concurrency model

Start with:

-   One selector/event-loop thread.
-   A bounded worker pool for application work.
-   A separate bounded pool or controlled executor for file and
    compression operations.
-   Per-connection input buffers and outgoing queues.

Do not create an unlimited thread per chunk or per incoming request. Add
multiple selector threads only after measuring a need.

------------------------------------------------------------------------

## 5. Application Protocol Design

A custom protocol is one of the most valuable learning opportunities in
this project.

### 5.1 Why framing is necessary

TCP does not preserve application message boundaries. If a sender writes
two messages, the receiver may read them together, separately, or in
partial pieces.

The application therefore needs a framing strategy.

A length-prefixed frame is a suitable starting point.

### 5.2 Example packet header

One possible header is:

  Field                    Size Purpose
  ------------------ ---------- ---------------------------------------------
  Magic number          4 bytes Identify packets belonging to this protocol
  Protocol version       1 byte Identify the protocol version
  Message type           1 byte Identify the packet's purpose
  Flags                 2 bytes Indicate compression or other options
  Payload length        4 bytes State the payload size
  Request ID            8 bytes Correlate requests and responses
  Payload              Variable Carry the message or binary data

The example header is 20 bytes. Define byte order explicitly, for
example big-endian, and keep the encoding consistent across clients and
server.

### 5.3 Suggested message types

Possible message types include:

-   `AUTH_REQUEST`
-   `AUTH_RESPONSE`
-   `TEXT_MESSAGE`
-   `GROUP_MESSAGE`
-   `USER_STATUS`
-   `TYPING_STATUS`
-   `DELIVERY_ACK`
-   `READ_RECEIPT`
-   `FILE_TRANSFER_REQUEST`
-   `FILE_TRANSFER_ACCEPT`
-   `FILE_TRANSFER_CHUNK`
-   `FILE_TRANSFER_COMPLETE`
-   `FILE_TRANSFER_CANCEL`
-   `ERROR_RESPONSE`
-   `PING`
-   `PONG`

The exact list should evolve with the application.

### 5.4 Hybrid serialization strategy

A practical initial design:

-   Binary header for every frame.
-   JSON payloads for control messages and metadata.
-   Raw binary payloads for files, images, and audio.
-   Optional compression for payloads where compression is beneficial.

Avoid Base64-encoding file data into JSON. It increases payload size and
creates unnecessary encoding and decoding work.

### 5.5 Protocol safety and versioning

-   Validate the magic number and protocol version.
-   Validate message types and flags.
-   Reject invalid or unsupported frames.
-   Enforce a maximum payload length before allocating memory.
-   Validate metadata and authorization independently.
-   Define how older and newer protocol versions interact.
-   Do not trust a sender-provided filename, file size, user ID, or
    group ID without validation.

A declared payload length must never be allowed to trigger an unbounded
memory allocation.

------------------------------------------------------------------------

## 6. File Transfer Subsystem

Treat file transfer as a dedicated subsystem rather than as an ordinary
chat message.

### 6.1 Suggested transfer lifecycle

1.  The sender submits a transfer request.
2.  The request includes metadata such as filename, size, media type,
    and expected checksum.
3.  The server validates permissions, size limits, and available
    storage.
4.  The server creates a unique transfer ID.
5.  The sender transmits file chunks.
6.  The receiver or server writes chunks to storage.
7.  The completed file is reconstructed.
8.  The receiver calculates a checksum and verifies integrity.
9.  The server marks the transfer complete and notifies the recipient.

The exact routing model depends on whether files are relayed through the
server or transferred directly between clients. A server-relayed design
is simpler to control and secure initially.

### 6.2 Chunk metadata

Each chunk should be associated with enough information to identify its
place in a transfer:

-   Transfer ID.
-   Chunk index or byte offset.
-   Chunk length.
-   Optional chunk checksum.
-   Payload bytes.

The transfer metadata should also track:

-   Original filename.
-   Declared file size.
-   Total number of chunks, if using fixed-size chunks.
-   Expected whole-file checksum.
-   Sender and intended recipient or group.
-   Current transfer state.

### 6.3 Chunked streaming

Do not load an entire large file into memory.

Use streaming reads and writes. Java's `FileChannel` is useful for file
access, including positional reads and writes at known offsets.

Chunking provides:

-   Bounded memory use.
-   Progress reporting.
-   Recovery from interrupted transfers.
-   A basis for parallel transfer experiments.
-   Easier validation of received data.

### 6.4 Initial transfer settings

These are experimental starting points, not universally optimal values.

  Parameter                                       Initial value
  ------------------------------- -----------------------------
  Chunk size                                             256 KB
  Concurrent transfers per user                               2
  Transfer worker count                                       4
  Maximum file size                                        1 GB
  Integrity algorithm                                   SHA-256
  Compression                       Optional and type-dependent
  Resume support                        Later development phase

Measure performance and adjust these settings based on the test
environment.

### 6.5 Resumable transfers

A later version should support resuming an interrupted transfer.

A possible workflow:

1.  The client reconnects.
2.  It presents the transfer ID.
3.  The server checks the transfer's authorization and state.
4.  The server reports which chunks or byte ranges are missing.
5.  The sender transmits only the missing data.
6.  The receiver verifies the completed file.

For a first implementation, fixed-size chunks and a received-chunk map
are relatively straightforward.

------------------------------------------------------------------------

## 7. Compression and Parallel Transfers

### 7.1 Compression algorithms to investigate

  Algorithm       Characteristics
  --------------- ----------------------------------------------------------
  Deflate / ZIP   General-purpose compression
  GZIP            Widely supported compression format
  Zstandard       Strong balance of speed and compression ratio
  LZ4             Very fast compression, generally lower compression ratio

Zstandard is a good candidate to benchmark first.

### 7.2 Compression is not always beneficial

Compression can help with:

-   Text files.
-   JSON.
-   Logs.
-   Other data containing repeated patterns.

It often provides little benefit for:

-   JPEG and PNG images.
-   MP3 and AAC audio.
-   MP4 video.
-   ZIP and other compressed archives.

Compressing already-compressed data can waste CPU time and may slightly
increase the data size.

Make compression optional and indicate it through a protocol flag. The
receiver must know whether a payload is compressed and how to decode it.

### 7.3 Parallel chunk transfer

A file can be divided into independent chunks and processed by a bounded
worker pool.

For example, different workers can read and send different ranges of a
file. The receiver can write each chunk at its corresponding offset.

However, multiple workers do not automatically make transfers faster.

Performance depends on:

-   Available bandwidth.
-   Network latency.
-   TCP congestion control.
-   Disk read and write speed.
-   CPU usage.
-   Server and receiver capacity.
-   Number of simultaneous clients.

Start with one transfer stream. Benchmark a small number of concurrent
workers later.

### 7.4 Important distinction: TCP segmentation and application chunking

TCP already segments the byte stream internally. Application-level
chunks serve a different purpose: progress reporting, validation,
resumption, bounded memory use, and optional parallelism.

Do not implement application chunking merely because TCP needs help
splitting packets. Implement it because the application needs control
over file-transfer state.

------------------------------------------------------------------------

## 8. One TCP Connection or Separate Transfer Connections?

There are three reasonable approaches.

  -----------------------------------------------------------------------
  Approach                Advantages              Disadvantages
  ----------------------- ----------------------- -----------------------
  One connection for      Simple connection       Large transfers can
  everything              management              delay later application
                                                  data in the TCP byte
                                                  stream

  Separate connection for Chat traffic is more    More connection and
  bulk transfers          independent of file     authentication
                          traffic                 management

  Multiple connections    Allows parallel         More complexity and
  for parallel chunks     transfer experiments    possible
                                                  congestion/resource
                                                  overhead
  -----------------------------------------------------------------------

A sensible progression is:

1.  Begin with one TCP connection per client.
2.  Implement reliable framing and bounded outgoing queues.
3.  Introduce separate bulk-transfer connections if needed.
4.  Experiment with multiple connections only after benchmarking.

TCP provides ordered delivery. If a large file is being sent on the same
connection as chat messages, later chat bytes cannot overtake earlier
file bytes. Application-level priority alone cannot change that
ordering.

------------------------------------------------------------------------

## 9. Database and Storage

SQLite is sufficient for an initial desktop-oriented or small-server
project.

### 9.1 Suggested tables

  Table                    Purpose
  ------------------------ --------------------------------------
  `users`                  User accounts and profile metadata
  `conversations`          Private conversations and groups
  `conversation_members`   Membership and roles
  `messages`               Message history
  `attachments`            File metadata and storage references
  `transfers`              Transfer status and progress
  `message_receipts`       Delivery and read status

### 9.2 Store file content on the filesystem

For the initial design, store file bytes on disk and keep metadata in
the database.

Example storage structure:

``` text
storage/
├── attachments/
│   └── 2026/
│       └── 11/
│           ├── transfer-001.bin
│           └── transfer-002.bin
├── temporary/
│   └── incomplete/
└── avatars/
```

Use generated storage names rather than trusting user-provided filenames
as filesystem paths.

The database should record the original filename, generated storage key,
size, media type, checksum, owner, and transfer state.

------------------------------------------------------------------------

## 10. GUI Architecture

JavaFX is a suitable option for a Java desktop client.

### 10.1 Suggested interface components

-   Conversation sidebar.
-   Private and group conversations.
-   Message history.
-   Message composer.
-   File attachment selector.
-   Image and document previews.
-   Transfer progress panel.
-   User presence indicators.
-   Connection status.
-   Settings.

### 10.2 Keep networking off the JavaFX Application Thread

Network operations must not block the JavaFX Application Thread.

Use background networking and application services. Update JavaFX
controls through `Platform.runLater()` or an appropriate
observable-state mechanism.

This keeps the interface responsive during connection attempts, message
reception, and large file transfers.

------------------------------------------------------------------------

## 11. Security

Security should be considered from the beginning, even if some features
are implemented in later phases.

### 11.1 Transport encryption

Use TLS to protect communication between clients and server.

Relevant Java APIs include:

-   `SSLContext`
-   `SSLEngine`
-   Java's standard TLS support

`SSLEngine` can be integrated with non-blocking channels, but it adds
complexity because the application must manage encrypted network buffers
and decrypted application buffers.

It is reasonable to first understand the NIO design without TLS, then
introduce TLS before exposing the application to untrusted networks.

### 11.2 Authentication and authorization

Implement:

-   User authentication.
-   Session or token management.
-   Authorization checks for private conversations.
-   Group membership checks.
-   Access control for attachments.
-   Protection against impersonating another sender.

Never trust a sender ID or recipient ID merely because it appears in an
incoming packet.

### 11.3 File security

Implement:

-   File-size limits.
-   Strict path handling.
-   Generated storage filenames.
-   Filename and media-type validation.
-   Storage quotas.
-   Limits on concurrent transfers.
-   Cleanup of abandoned temporary files.
-   Protection against path traversal.

Do not rely solely on file extensions to determine file content.

### 11.4 Password storage

Never store plaintext passwords.

Use a suitable password-hashing algorithm such as Argon2id or PBKDF2,
with unique salts and appropriately configured parameters.

### 11.5 Integrity versus authentication

SHA-256 is useful for checking whether a reconstructed file matches the
expected content.

However, a checksum alone does not prove who sent the file. Transport
authentication and, if required, end-to-end cryptographic authentication
are separate concerns.

### 11.6 End-to-end encryption

End-to-end encryption is an advanced feature. It requires careful key
management, identity verification, secure message encryption, and
handling of group membership changes.

Do not invent a custom cryptographic protocol. If this becomes a project
requirement, study established, reviewed protocols and libraries.

------------------------------------------------------------------------

## 12. Testing and Performance Benchmarking

Avoid assuming that more threads, larger chunks, or compression will
improve performance. Measure them.

### 12.1 Suggested benchmarks

  Test     Chunk size   Workers
  ------ ------------ ---------
  A              1 MB         1
  B              1 MB         2
  C              1 MB         4
  D              4 MB         2
  E              4 MB         4

These are example configurations for experiments, not guaranteed optimal
settings.

### 12.2 Metrics to collect

-   Average transfer speed in MB/s.
-   Total transfer duration.
-   CPU utilization.
-   Memory consumption.
-   Disk read/write throughput.
-   Number of connected clients.
-   Number of simultaneous transfers.
-   Queue sizes and queue delays.
-   Failed and interrupted transfers.
-   Recovery time after reconnection.

TCP retransmissions are handled by the operating system.
Application-level missing-chunk recovery is a separate mechanism.

### 12.3 Failure scenarios to test

-   Client disconnects during a file transfer.
-   Server restarts during a transfer.
-   Receiver runs out of disk space.
-   Sender declares an invalid payload length.
-   Client sends incomplete or malformed frames.
-   Recipient is offline.
-   A recipient reads more slowly than the sender transmits.
-   Several clients transfer large files simultaneously.
-   A user attempts to access another user's attachment.

### 12.4 Useful tools

-   JUnit for unit and integration testing.
-   Wireshark for inspecting network traffic.
-   Java Flight Recorder for profiling.
-   VisualVM or another JVM monitoring tool.
-   Operating-system monitoring tools for CPU, memory, and disk
    activity.

------------------------------------------------------------------------

## 13. Recommended Development Roadmap

Build the application in phases. Each phase should produce something
testable before moving to the next.

### Phase 1 --- Communication foundation

-   Create a Java NIO server.
-   Accept multiple clients.
-   Implement client connections.
-   Implement correct packet framing.
-   Handle partial reads and writes.
-   Implement one-to-one messaging.
-   Implement group messaging.

**Completion criterion:** Multiple clients can exchange messages
reliably without confusing packet boundaries.

### Phase 2 --- Desktop client

-   Build the JavaFX interface.
-   Add a conversation list.
-   Display private and group messages.
-   Connect the GUI to application services.
-   Keep networking off the JavaFX Application Thread.
-   Handle disconnections and reconnections.

**Completion criterion:** Users can communicate through a functional
desktop interface.

### Phase 3 --- Basic file transfer

-   Implement transfer requests.
-   Stream files in chunks.
-   Reconstruct files.
-   Display transfer progress.
-   Verify SHA-256 checksums.
-   Enforce file-size and storage limits.

**Completion criterion:** Users can reliably transfer files without
loading entire files into memory.

### Phase 4 --- Concurrency and optimization

-   Introduce bounded worker pools.
-   Separate expensive operations from selector threads.
-   Add optional compression.
-   Benchmark chunk sizes and worker counts.
-   Investigate separate file-transfer connections.

**Completion criterion:** Performance decisions are based on
measurements.

### Phase 5 --- Reliability

-   Implement resumable transfers.
-   Track missing chunks.
-   Add delivery acknowledgements.
-   Persist message history.
-   Handle offline recipients.
-   Clean up abandoned transfers.

**Completion criterion:** The application recovers gracefully from
common failures.

### Phase 6 --- Security

-   Add authentication.
-   Add authorization.
-   Introduce TLS.
-   Secure password storage.
-   Validate files and metadata.
-   Protect attachments and storage paths.
-   Add resource and rate limits.

**Completion criterion:** The server enforces identity, permissions, and
resource boundaries.

### Phase 7 --- Advanced features

-   Voice-note recording and playback.
-   Image previews.
-   Typing indicators.
-   Online/offline presence.
-   Read receipts.
-   More advanced group management.
-   Optional end-to-end encryption.
-   Optional live voice communication through a technology such as
    WebRTC.

**Completion criterion:** The application has a broader messaging
feature set without compromising its core reliability.

------------------------------------------------------------------------

## 14. Technologies and Concepts Worth Investigating

  -----------------------------------------------------------------------
  Technology or concept               Why it matters
  ----------------------------------- -----------------------------------
  Java NIO                            Non-blocking network communication

  Netty                               Mature asynchronous networking
                                      framework; useful for comparison
                                      after learning raw NIO

  Protocol Buffers                    Compact structured serialization

  Zstandard                           Fast general-purpose compression

  TLS                                 Encrypted transport

  WebRTC                              Real-time audio and video
                                      communication

  Wireshark                           Network packet inspection

  Producer-consumer pattern           Separating network reception,
                                      processing, and sending

  Backpressure                        Preventing slow consumers from
                                      exhausting memory

  Event-driven architecture           Organizing work around I/O and
                                      application events

  Protocol versioning                 Evolving the protocol without
                                      unnecessarily breaking clients
  -----------------------------------------------------------------------

Do not adopt every technology immediately. Understanding the underlying
concepts is more important than accumulating dependencies.

------------------------------------------------------------------------

## 15. Suggested Maven Project Structure

A possible logical structure is:

``` text
chat-application/
│
├── common/
│   ├── protocol/
│   ├── serialization/
│   ├── models/
│   └── constants/
│
├── server/
│   ├── network/
│   │   ├── NioServer.java
│   │   ├── Connection.java
│   │   ├── SelectorManager.java
│   │   └── PacketDecoder.java
│   │
│   ├── handlers/
│   │   ├── AuthenticationHandler.java
│   │   ├── MessageHandler.java
│   │   ├── GroupHandler.java
│   │   └── TransferHandler.java
│   │
│   ├── transfer/
│   ├── storage/
│   ├── database/
│   └── security/
│
├── client/
│   ├── network/
│   ├── services/
│   ├── transfer/
│   └── gui/
│       ├── controllers/
│       ├── views/
│       └── components/
│
└── pom.xml
```

This is a logical organization, not a requirement to create every
package at the beginning. The `common`, `server`, and `client`
directories can eventually become separate Maven modules.

------------------------------------------------------------------------

## 16. Key Architectural Decisions

The following are recommended initial decisions. Revisit them when
benchmarks or requirements provide a reason.

  Decision                       Initial recommendation
  ------------------------------ ------------------------------------
  Network API                    Java NIO
  Connection handling            Selector-based event loop
  Initial selector count         One
  Background processing          Bounded worker pools
  Message framing                Length-prefixed binary frames
  Control payloads               JSON initially
  File payloads                  Raw binary
  File transfer                  Chunked streaming
  File storage                   Filesystem
  Metadata storage               SQLite
  Compression                    Optional Zstandard
  Initial transfer connections   One connection per client
  GUI                            JavaFX
  Integrity verification         SHA-256
  Security                       TLS, authentication, authorization

------------------------------------------------------------------------

## 17. Learning Priorities

To understand the system rather than simply assemble it, focus on these
three subjects.

### 17.1 Event-driven architecture

Study:

-   `Selector`
-   `SelectionKey`
-   `SocketChannel`
-   Event loops
-   Readiness notifications
-   Partial reads and writes

**Question to answer:** How can one selector thread manage many
connections without blocking on an individual client?

### 17.2 Producer-consumer architecture

Study:

-   `BlockingQueue`
-   `ExecutorService`
-   `ThreadPoolExecutor`
-   Bounded queues
-   Backpressure
-   Task rejection and cancellation

**Question to answer:** How can incoming network data be processed
without blocking the event loop or allowing unlimited work to
accumulate?

### 17.3 Protocol design

Study:

-   Message framing
-   Binary encoding
-   Serialization
-   Protocol versioning
-   Request IDs
-   Acknowledgements
-   Error responses

**Question to answer:** How can the protocol evolve while remaining
predictable, safe, and compatible with clients?

------------------------------------------------------------------------

## 18. Final Recommendations

1.  Start with the NIO server and a small number of clients.
2.  Make packet framing correct before implementing application
    features.
3.  Keep the selector event loop free of blocking work.
4.  Use bounded queues and worker pools.
5.  Implement ordinary file streaming before parallel transfers.
6.  Benchmark before introducing compression or multiple TCP
    connections.
7.  Design file transfers to support integrity checks and eventual
    resumption.
8.  Keep GUI, application logic, protocol, and transport concerns
    separate.
9.  Treat security and resource limits as architectural requirements.
10. Build incrementally and test failure scenarios, not only successful
    demonstrations.

The strongest version of this project is not necessarily the one with
the most threads or the most complicated protocol. It is the one whose
behaviour is reliable, whose performance has been measured, and whose
components have clear responsibilities.
