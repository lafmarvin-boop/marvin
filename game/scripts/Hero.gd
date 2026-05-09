class_name Hero
extends Node2D

# Héros placé sur la grille. Stationnaire, attaque l'ennemi le plus proche dans sa portée.

signal died(hero)

var stats: Dictionary
var max_hp: int = 1
var hp: int = 1
var attack_cooldown: float = 0.0
var col: int = 0
var row: int = 0

var battle_ref: Node = null
var hero_inst: Dictionary = {}

var sprite: ColorRect
var hp_bar: ColorRect
var hp_bar_bg: ColorRect

func setup(_stats: Dictionary, _hero_inst: Dictionary, _battle: Node) -> void:
	stats = _stats
	hero_inst = _hero_inst
	battle_ref = _battle
	max_hp = int(stats["max_hp"])
	hp = max_hp

func _ready() -> void:
	# Corps
	sprite = ColorRect.new()
	sprite.color = stats["color"]
	sprite.size = Vector2(64, 80)
	sprite.position = Vector2(-32, -40)
	add_child(sprite)

	# Bordure
	var border := ReferenceRect.new()
	border.position = sprite.position
	border.size = sprite.size
	border.border_color = Color(0, 0, 0, 0.6)
	border.editor_only = false
	add_child(border)

	# Initiale du nom
	var initial := Label.new()
	initial.text = String(stats["name"]).substr(0, 1)
	initial.position = Vector2(-12, -28)
	initial.add_theme_font_size_override("font_size", 32)
	initial.add_theme_color_override("font_color", Color(0.05, 0.05, 0.1))
	add_child(initial)

	# Niveau
	var lvl := Label.new()
	lvl.text = "Lv%d" % int(stats["level"])
	lvl.position = Vector2(-22, 22)
	lvl.add_theme_font_size_override("font_size", 14)
	lvl.add_theme_color_override("font_color", Color(0.05, 0.05, 0.1))
	add_child(lvl)

	# Barre de vie
	hp_bar_bg = ColorRect.new()
	hp_bar_bg.color = Color(0.18, 0.04, 0.04)
	hp_bar_bg.size = Vector2(64, 6)
	hp_bar_bg.position = Vector2(-32, -52)
	add_child(hp_bar_bg)

	hp_bar = ColorRect.new()
	hp_bar.color = Color(0.30, 0.95, 0.35)
	hp_bar.size = Vector2(64, 6)
	hp_bar.position = Vector2(-32, -52)
	add_child(hp_bar)

func _process(delta: float) -> void:
	if not battle_ref or not is_instance_valid(battle_ref):
		return
	if battle_ref.phase != battle_ref.Phase.COMBAT:
		return
	attack_cooldown -= delta
	if attack_cooldown <= 0:
		var target := _find_target()
		if target:
			_attack(target)
			var aspeed: float = float(stats["attack_speed"])
			attack_cooldown = 1.0 / max(0.1, aspeed)

func _find_target():
	var best = null
	var best_dist := 9999.0
	var range_cells: int = int(stats["range"])
	var range_px: float = float(range_cells) * 100.0
	if String(stats["type"]) == "melee":
		range_px = max(95.0, range_px)
	for e in battle_ref.enemies_alive:
		if not is_instance_valid(e) or e.hp <= 0:
			continue
		var d: float = position.distance_to(e.position)
		if d <= range_px and d < best_dist:
			best = e
			best_dist = d
	return best

func _attack(target) -> void:
	target.take_damage(int(stats["attack"]))
	_spawn_attack_visual(target)

func _spawn_attack_visual(target) -> void:
	var line := Line2D.new()
	line.add_point(Vector2.ZERO)
	line.add_point(target.position - position)
	line.width = 3.0
	line.default_color = Color(stats["color"]).lightened(0.2)
	add_child(line)
	var tw := create_tween()
	tw.tween_property(line, "modulate:a", 0.0, 0.18)
	tw.tween_callback(line.queue_free)

func take_damage(amount: int) -> void:
	var dmg: int = max(1, amount - int(stats["defense"]))
	hp -= dmg
	if hp_bar:
		hp_bar.size.x = 64.0 * max(0.0, float(hp) / float(max_hp))
	# Flash
	var tw := create_tween()
	tw.tween_property(sprite, "modulate", Color(2, 0.6, 0.6), 0.05)
	tw.tween_property(sprite, "modulate", Color(1, 1, 1), 0.15)
	if hp <= 0:
		emit_signal("died", self)
		queue_free()
