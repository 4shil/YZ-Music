"""Comprehensive tests for upgraded Play Together backend capabilities."""

import pytest
from fastapi.testclient import TestClient

from app import config, main, protocol
from app.main import app
from app.party import Track

HOST = {"userId": "u-host", "deviceId": "d-host", "displayName": "Ash", "avatarUrl": "https://example.com/ash.jpg"}
GUEST = {"userId": "u-guest", "deviceId": "d-guest", "displayName": "Alex", "avatarUrl": "https://example.com/alex.jpg"}
THIRD = {"userId": "u-third", "deviceId": "d-third", "displayName": "Sarah", "avatarUrl": "https://example.com/sarah.jpg"}


@pytest.fixture(autouse=True)
def a_clean_server():
    main.store.clear()
    yield
    main.store.clear()


@pytest.fixture
def client():
    return TestClient(app)


def create(client, max_members: int = 5, host_only: bool = False) -> dict:
    req = {**HOST, "maxMembers": max_members, "hostOnlyControl": host_only}
    response = client.post("/api/parties", json=req)
    assert response.status_code == 201
    return response.json()


def join(client, code: str, user=GUEST) -> dict:
    response = client.post(f"/api/parties/{code}/join", json=user)
    assert response.status_code == 200
    return response.json()


def test_party_preview_endpoint(client):
    host = create(client, max_members=4)
    join(client, host["code"], GUEST)

    resp = client.get(f"/api/parties/{host['code']}/preview")
    assert resp.status_code == 200
    data = resp.json()
    assert data["code"] == host["code"]
    assert data["hostName"] == "Ash"
    assert data["memberCount"] == 2
    assert data["maxMembers"] == 4
    assert data["isFull"] is False
    assert len(data["members"]) == 2
    assert data["members"][0]["displayName"] == "Ash"
    assert data["members"][0]["isHost"] is True
    assert data["members"][1]["displayName"] == "Alex"
    assert data["members"][1]["isHost"] is False


def test_queue_add_remove_clear_and_move(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()  # welcome

        # 1. Add tracks
        socket.send_json({
            "type": "control",
            "action": "queueAdd",
            "tracks": [
                {"videoId": "s1", "title": "Song 1", "artist": "Artist 1"},
                {"videoId": "s2", "title": "Song 2", "artist": "Artist 2"},
                {"videoId": "s3", "title": "Song 3", "artist": "Artist 3"},
            ],
        })
        q_frame = socket.receive_json()
        assert q_frame["type"] == "queue"
        assert len(q_frame["queue"]["items"]) == 3
        assert [t["videoId"] for t in q_frame["queue"]["items"]] == ["s1", "s2", "s3"]
        socket.receive_json()  # state frame

        # 2. Move upcoming track: move index 2 ("s3") to index 0 -> s3, s1, s2
        socket.send_json({
            "type": "control",
            "action": "queueMove",
            "fromIndex": 2,
            "toIndex": 0,
            "videoId": "s3",
        })
        q_frame = socket.receive_json()
        assert q_frame["type"] == "queue"
        assert [t["videoId"] for t in q_frame["queue"]["items"]] == ["s3", "s1", "s2"]
        socket.receive_json()  # state frame

        # 3. Remove track by videoId: remove "s1"
        socket.send_json({
            "type": "control",
            "action": "queueRemove",
            "videoId": "s1",
        })
        q_frame = socket.receive_json()
        assert q_frame["type"] == "queue"
        assert [t["videoId"] for t in q_frame["queue"]["items"]] == ["s3", "s2"]
        socket.receive_json()  # state frame

        # 4. Clear upcoming queue
        socket.send_json({
            "type": "control",
            "action": "queueClear",
        })
        q_frame = socket.receive_json()
        assert q_frame["type"] == "queue"
        assert len(q_frame["queue"]["items"]) == 0
        socket.receive_json()  # state frame


def test_queue_cap_enforced_at_25_upcoming(client):
    host = create(client)
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as socket:
        socket.receive_json()  # welcome

        # Add 25 tracks (should succeed)
        tracks_25 = [{"videoId": f"track_{i}", "title": f"T{i}"} for i in range(25)]
        socket.send_json({
            "type": "control",
            "action": "queueAdd",
            "tracks": tracks_25,
        })
        q_frame = socket.receive_json()
        assert q_frame["type"] == "queue"
        assert len(q_frame["queue"]["items"]) == 25
        socket.receive_json()  # state

        # Attempt to add 1 more track (should be refused with queue_full)
        socket.send_json({
            "type": "control",
            "action": "queueAdd",
            "tracks": [{"videoId": "overflow_track"}],
        })
        err_frame = socket.receive_json()
        assert err_frame["type"] == "error"
        assert err_frame["error"] == "queue_full"


def test_host_only_control_policy(client):
    # Host creates a party with hostOnlyControl=True
    host = create(client, host_only=True)
    guest = join(client, host["code"], GUEST)

    with client.websocket_connect(f"/ws/parties/{host['code']}?token={guest['token']}") as guest_sock:
        guest_sock.receive_json()  # welcome

        # Guest attempts to play
        guest_sock.send_json({"type": "control", "action": "play"})
        err = guest_sock.receive_json()
        assert err["type"] == "error"
        assert err["error"] == "host_only"

        # Guest attempts to add to queue
        guest_sock.send_json({
            "type": "control",
            "action": "queueAdd",
            "tracks": [{"videoId": "song"}],
        })
        err = guest_sock.receive_json()
        assert err["type"] == "error"
        assert err["error"] == "host_only"

    # Host connects and can control
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as host_sock:
        host_sock.receive_json()  # welcome
        host_sock.send_json({"type": "control", "action": "play", "positionMs": 500})
        st = host_sock.receive_json()
        assert st["type"] == "state"
        assert st["playback"]["isPlaying"] is True


def test_kick_member_and_receive_bye(client):
    host = create(client)
    guest = join(client, host["code"], GUEST)

    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}") as host_sock:
        host_sock.receive_json()  # welcome
        with client.websocket_connect(f"/ws/parties/{host['code']}?token={guest['token']}") as guest_sock:
            guest_sock.receive_json()  # welcome
            host_sock.receive_json()   # members frame (guest joined)

            # Host kicks guest
            host_sock.send_json({
                "type": "control",
                "action": "kick",
                "memberId": guest["you"]["memberId"],
            })

            # Guest receives bye frame with reason 'kicked'
            bye = guest_sock.receive_json()
            assert bye["type"] == "bye"
            assert bye["reason"] == "kicked"


def test_activity_subscription_and_feed_endpoint(client):
    host = create(client)
    # Connect with ?activity=true to receive live activity frames
    with client.websocket_connect(f"/ws/parties/{host['code']}?token={host['token']}&activity=true") as socket:
        socket.receive_json()  # welcome

        # Play action
        socket.send_json({"type": "control", "action": "play", "positionMs": 0})
        st = socket.receive_json()
        assert st["type"] == "state"
        act = socket.receive_json()
        assert act["type"] == "activity"
        assert act["action"] == "play"
        assert act["by"] == "Ash"

    # Query activity endpoint
    feed_resp = client.get(f"/api/parties/{host['code']}/activity")
    assert feed_resp.status_code == 200
    feed = feed_resp.json()
    assert len(feed) >= 1
    assert feed[-1]["action"] == "play"
    assert feed[-1]["by"] == "Ash"
