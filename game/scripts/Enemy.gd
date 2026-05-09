class_name Enemy
extends Node2D

# Ennemi qui descend vers le château. S'arrête pour attaquer un héros au corps à corps.

signal died(enemy, gold: int, xp: int)
signal reached_castle(enemy)

var stats: Dictionary
var max_hp: int = 1
var hp: int = 1
var attack_cooldown: float = 0.0

var battle_ref: Node = null

var sprite: ColorRect
var hp_bar: ColorRect
var hp_bar_bg: ColorRect

const MELEE_REACH := 75.0

func setup(_stats: Dictionary, _battle: Node) -> void:
	stats = _stats
	battle_ref = _battle
	max_hp = int(stats["max_hp"])
	hp = max_hp

func _ready() -> void:
	var size: float = 56.0
	if stats.get("is_boss", false):
		size = 96.0

	sprite = ColorRect.new()
	sprite.color = stats["color"]
	sprite.size = Vector2(size, size)
	sprite.position = Vector2(-size * 0.5, -size * 0.5)
	add_child(sprite)

	var border := ReferenceRect.new()
	border.position = sprite.position
	border.size = sprite.size
	border.border_color = Color(0, 0, 0, 0.7)
	border.editor_only = false
	add_child(border)

	var initial := Label.new()
	initial.text = String(stats["name"]).substr(0, 1)
	initial.position = Vector2(-10, -size * 0.5 + 6)
	initial.add_theme_font_size_override("font_size", int(size * 0.45))
	initial.add_theme_color_override("font_color", Color(1, 1, 1))
	add_child(initial)

	hp_bar_bg = ColorRect.new()
	hp_bar_bg.color = Color(0.18, 0.04, 0.04)
	hp_bar_bg.size = Vector2(size, 5)
	hp_bar_bg.position = Vector2(-size * 0.5, -size * 0.5 - 10)
	add_child(hp_bar_bg)

	hp_bar = ColorRect.new()
	hp_bar.color = Color(0.95, 0.25, 0.25)
	hp_bar.size = Vector2(size, 5)
	hp_bar.position = Vector2(-size * 0.5, -size * 0.5 - 10)
	add_child(hp_bar)

func _process(delta: float) -> void:
	if not battle_ref or not is_instance_valid(battle_ref):
		return
	if battle_ref.phase != battle_ref.Phase.COMBAT:
		return

	var melee_target = _find_blocking_hero()
	if melee_target:
		attack_cooldown -= delta
		if attack_cooldown <= 0:
			melee_target.take_damage(int(stats["attack"]))
			_spawn_attack_visual(melee_target)
			var aspeed: float = float(stats["attack_speed"])
			attack_cooldown = 1.0 / max(0.1, aspeed)
	else:
		var dy: float = float(stats["move_speed"]) * delta
		position.y += dy
		if position.y >= float(battle_ref.CASTLE_Y) - 60.0:
			emit_signal("reached_castle", self)
			queue_free()

func _find_blocking_hero():
	for h in battle_ref.heroes_alive:
		if not is_instance_valid(h) or h.hp <= 0:
			continue
		if position.distance_to(h.position) < MELEE_REACH:
			return h
	return null

func _spawn_attack_visual(target) -> void:
	var line := Line2D.new()
	line.add_point(Vector2.ZERO)
	line.add_point(target.position - position)
	line.width = 2.5
	line.default_color = Color(1.0, 0.5, 0.3)
	add_child(line)
	var tw := create_tween()
	tw.tween_property(line, "modulate:a", 0.0, 0.15)
	tw.tween_callback(line.queue_free)

func take_damage(amount: int) -> void:
	var dmg: int = max(1, amount - int(stats["defense"]))
	hp -= dmg
	if hp_bar:
		hp_bar.size.x = hp_bar_bg.size.x * max(0.0, float(hp) / float(max_hp))
	var tw := create_tween()
	tw.tween_property(sprite, "modulate", Color(2, 0.6, 0.6), 0.05)
	tw.tween_property(sprite, "modulate", Color(1, 1, 1), 0.15)
	if hp <= 0:
		emit_signal("died", self, int(stats["gold_reward"]), int(stats["xp_reward"]))
		queue_free()
