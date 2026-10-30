CREATE TABLE player_chest_contents (
    chest_key TEXT NOT NULL,
    player_uuid TEXT NOT NULL,
    contents TEXT NOT NULL,
    PRIMARY KEY (chest_key, player_uuid)
);
