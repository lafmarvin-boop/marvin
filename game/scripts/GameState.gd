extends Node

# État global persistant entre les scènes.

var current_floor: int = 1
var max_floor_reached: int = 1
var gold: int = 100

var hero_collection: Array = []
var battle_party: Array = []

const HEROES_DATA := {
	"knight": {
		"name": "Chevalier",
		"color": Color(0.85, 0.85, 0.92),
		"max_hp": 220,
		"attack": 16,
		"defense": 8,
		"attack_speed": 1.0,
		"range": 1,
		"type": "melee"
	},
	"archer": {
		"name": "Archer",
		"color": Color(0.30, 0.80, 0.35),
		"max_hp": 110,
		"attack": 22,
		"defense": 2,
		"attack_speed": 1.4,
		"range": 4,
		"type": "ranged"
	},
	"mage": {
		"name": "Mage",
		"color": Color(0.40, 0.45, 1.00),
		"max_hp": 95,
		"attack": 32,
		"defense": 1,
		"attack_speed": 0.7,
		"range": 5,
		"type": "ranged"
	},
	"tank": {
		"name": "Garde",
		"color": Color(0.60, 0.40, 0.20),
		"max_hp": 380,
		"attack": 9,
		"defense": 14,
		"attack_speed": 0.8,
		"range": 1,
		"type": "melee"
	},
	"hero_m": {
		"name": "Hero M",
		"color": Color(0.85, 0.20, 0.20),
		"max_hp": 260,
		"attack": 24,
		"defense": 6,
		"attack_speed": 1.1,
		"range": 1,
		"type": "melee",
		"sprite_walk_path": "res://assets/sprites/hero_m/walk.png",
		"sprite_frame_w": 40,
		"sprite_frame_h": 80,
		"sprite_walk_frames": 4,
		"sprite_walk_fps": 8
	}
}

const ENEMIES_DATA := {
	"slime": {
		"name": "Slime",
		"color": Color(0.45, 0.95, 0.50),
		"max_hp": 55,
		"attack": 6,
		"defense": 0,
		"attack_speed": 1.0,
		"range": 1,
		"move_speed": 55.0,
		"gold_reward": 5,
		"xp_reward": 4,
		"is_boss": false
	},
	"wolf": {
		"name": "Loup",
		"color": Color(0.55, 0.55, 0.60),
		"max_hp": 75,
		"attack": 14,
		"defense": 1,
		"attack_speed": 1.5,
		"range": 1,
		"move_speed": 95.0,
		"gold_reward": 8,
		"xp_reward": 6,
		"is_boss": false
	},
	"orc": {
		"name": "Orc",
		"color": Color(0.40, 0.65, 0.40),
		"max_hp": 130,
		"attack": 13,
		"defense": 3,
		"attack_speed": 0.9,
		"range": 1,
		"move_speed": 48.0,
		"gold_reward": 11,
		"xp_reward": 8,
		"is_boss": false
	},
	"boss_ogre": {
		"name": "Ogre",
		"color": Color(0.60, 0.18, 0.20),
		"max_hp": 850,
		"attack": 26,
		"defense": 10,
		"attack_speed": 0.6,
		"range": 1,
		"move_speed": 32.0,
		"gold_reward": 60,
		"xp_reward": 45,
		"is_boss": true
	}
}

func _ready() -> void:
	if hero_collection.is_empty():
		for id in ["hero_m", "archer", "mage", "tank"]:
			var inst := make_hero_instance(id)
			hero_collection.append(inst)
			battle_party.append(inst)

func make_hero_instance(hero_id: String) -> Dictionary:
	return {
		"id": hero_id,
		"level": 1,
		"exp": 0,
		"exp_to_next": 20
	}

func get_hero_stats(inst: Dictionary) -> Dictionary:
	var base: Dictionary = HEROES_DATA[inst["id"]]
	var lvl: int = inst["level"]
	var mult: float = 1.0 + float(lvl - 1) * 0.18
	var out := {
		"id": inst["id"],
		"name": base["name"],
		"color": base["color"],
		"max_hp": int(round(base["max_hp"] * mult)),
		"attack": int(round(base["attack"] * mult)),
		"defense": int(round(base["defense"] * mult)),
		"attack_speed": base["attack_speed"],
		"range": base["range"],
		"type": base["type"],
		"level": lvl
	}
	for k in ["sprite_walk_path", "sprite_frame_w", "sprite_frame_h",
			"sprite_walk_frames", "sprite_walk_fps"]:
		if base.has(k):
			out[k] = base[k]
	return out

func grant_xp(inst: Dictionary, amount: int) -> void:
	inst["exp"] = int(inst["exp"]) + amount
	while inst["exp"] >= inst["exp_to_next"]:
		inst["exp"] -= inst["exp_to_next"]
		inst["level"] = int(inst["level"]) + 1
		inst["exp_to_next"] = int(round(float(inst["exp_to_next"]) * 1.4))

func generate_wave(floor: int) -> Array:
	var diff: float = 1.0 + float(floor - 1) * 0.18
	var enemies: Array = []
	var is_boss_floor: bool = (floor % 5 == 0)
	if is_boss_floor:
		enemies.append({"id": "boss_ogre", "diff": diff, "delay": 0.5})
		var minions := 3 + int(floor / 5)
		for i in range(minions):
			enemies.append({"id": "orc", "diff": diff, "delay": 2.5 + i * 1.4})
	else:
		var count: int = 5 + floor
		var pool: Array = ["slime"]
		if floor >= 2:
			pool.append("wolf")
		if floor >= 3:
			pool.append("orc")
		var t: float = 0.5
		for i in range(count):
			var id: String = pool[randi() % pool.size()]
			enemies.append({"id": id, "diff": diff, "delay": t})
			t += randf_range(0.8, 1.6)
	return enemies

func get_scaled_enemy(enemy_id: String, diff: float) -> Dictionary:
	var base: Dictionary = ENEMIES_DATA[enemy_id]
	return {
		"id": enemy_id,
		"name": base["name"],
		"color": base["color"],
		"max_hp": int(round(base["max_hp"] * diff)),
		"attack": int(round(base["attack"] * diff)),
		"defense": int(round(base["defense"] * diff)),
		"attack_speed": base["attack_speed"],
		"range": base["range"],
		"move_speed": base["move_speed"],
		"gold_reward": base["gold_reward"],
		"xp_reward": base["xp_reward"],
		"is_boss": base["is_boss"]
	}
