## 1. The idea: Zero Knowledge

The backend should be physically incapable of every revealing data sent by the user

* **Primary Stack:** Java 26 with **Quarkus**.
* **Infrastructure:** Kubernetes
* **Communication:** Peer to Peer for persistent history.  

---

## 2. Microservice Architecture

### Identity Service

* **Responsibility:** User authentication, JWT issuance, and JWT validation.

* **Relies on:** PostgreSQL for user data and Redis for JWT issue/validation state.

* **Endpoints:**
    * `POST /login`: Authenticates users and returns a JWT.
    * `POST /signup`: Registers new users. Expects name and password.
    * `POST /logout`: Invalidates the JWT on the server side.
    * `POST /validate`: Checks if a JWT is still valid. Returns `{'valid': boolean, 'username': string}`.

---

### Delivery Service

* **Responsibility:** Facilitates public key discovery and fan out of encrypted sender keys to recipient devices.
* **Relies on:** PostgreSQL for temporary storage of pending keys.

* **Endpoints:**
    * `POST /asymmetric/upload`: Clients upload their public key bundles (one per device).
    * `GET /asymmetric/fetch`: Fetches the public key bundle for a given UUIDs. Return a public key bundle
      containing the public keys of all devices associated with that UUID.
        * Input: list of UUIDs in body. Output: `{"uuid": {"device_uuid": "public_key", ...}, ...}`
    * `POST /symmetric/upload`: Clients upload the encrypted sender keys (one per recipient device). In one large JSON.
        * Send a JSON `{"recipient_uuid": {"device_uuid": "encrypted_sender_key"}, ...}}`
    * `GET /symmetric/fetch?deviceId=<deviceId>`: Clients fetch any pending encrypted sender keys for their device.
    * `POST /ack`: Clients send an ACK after successfully decrypting a sender key. The server deletes the corresponding
      encrypted sender key from PostgreSQL to prevent re-delivery.

### Message Service

* **Responsibility:** Temporarily store encrypted message to support offline delivery and multi-device sync.
* **Storage:** PostgreSQL with a "store and delete" pattern. Redis for real-time message delivery notifications.

* **Endpoints:**
    * `POST /message`: Clients send encrypted messages to this endpoint. (Keep for up to 7 days)
      NOTE: The client must check `/asymmetric/fetch` to make sure it has the latest public keys for all recipient
      devices.
        * The message includes metadata:
            * `message_uuid`: A unique identifier for the message (UUID).
            * `chat_uuid`: The unique identifier for the chat.
            * `recipient_uuids`: A list of recipient UUIDs.
            * `encrypted_payload`: The actual encrypted message content.
            * `timestamp`: The time the message was sent (UNIX epoch).
            * `sender_device_uuid`: The UUID of the sender's device.
    * `POST /message/ack`: Clients send an ACK after successfully decrypting a message. The server deletes the
      corresponding
      message from PostgreSQL to prevent re-delivery.
    * `GET /message/fetch` Clients fetch all pending messages for their device.

### History Service (p2p)

* **Responsibility:** Facilitate peer-to-peer retrieval of message history between devices.
* Uses a peerjs-server for WebRTC.
* Clients are identified by their device UUIDs. A device can request message history from another device by establishing
  a connection through the peerjs-server. The first thing sent over the connection is a cryptographic challenge to
  verify the identity of the requesting device. The challenge is a random string that the requesting device must sign
  with its private key. If the signature is valid, the connection is established, else it is rejected immediately. One a
  verified connection is established the data is encrypted end-to-end, using the same keys as regular messages.

---
