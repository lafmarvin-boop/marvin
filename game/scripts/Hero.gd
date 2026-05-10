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

var sprite: CanvasItem  # ColorRect placeholder OU AnimatedSprite2D
var hp_bar: ColorRect
var hp_bar_bg: ColorRect

func setup(_stats: Dictionary, _hero_inst: Dictionary, _battle: Node) -> void:
	stats = _stats
	hero_inst = _hero_inst
	battle_ref = _battle
	max_hp = int(stats["max_hp"])
	hp = max_hp

func _ready() -> void:
	if stats.has("sprite_walk_path") and ResourceLoader.exists(stats["sprite_walk_path"]):
		_setup_animated_sprite()
	else:
		_setup_placeholder_sprite()

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

func _setup_placeholder_sprite() -> void:
	var rect := ColorRect.new()
	rect.color = stats["color"]
	rect.size = Vector2(64, 80)
	rect.position = Vector2(-32, -40)
	add_child(rect)
	sprite = rect

	var border := ReferenceRect.new()
	border.position = rect.position
	border.size = rect.size
	border.border_color = Color(0, 0, 0, 0.6)
	border.editor_only = false
	add_child(border)

	var initial := Label.new()
	initial.text = String(stats["name"]).substr(0, 1)
	initial.position = Vector2(-12, -28)
	initial.add_theme_font_size_override("font_size", 32)
	initial.add_theme_color_override("font_color", Color(0.05, 0.05, 0.1))
	add_child(initial)

func _setup_animated_sprite() -> void:
	var tex: Texture2D = load(stats["sprite_walk_path"])
	var fw: int = int(stats.get("sprite_frame_w", 40))
	var fh: int = int(stats.get("sprite_frame_h", 80))
	var n: int = int(stats.get("sprite_walk_frames", 4))
	var fps: float = float(stats.get("sprite_walk_fps", 8))

	var frames := SpriteFrames.new()
	frames.add_animation("idle")
	frames.set_animation_speed("idle", fps)
	frames.set_animation_loop("idle", true)
	for i in range(n):
		var atlas := AtlasTexture.new()
		atlas.atlas = tex
		atlas.region = Rect2(i * fw, 0, fw, fh)
		frames.add_frame("idle", atlas)

	var anim := AnimatedSprite2D.new()
	anim.sprite_frames = frames
	anim.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	# Décale pour que les pieds reposent au centre-bas de la cellule (cellule 100 px de haut)
	anim.position = Vector2(0, 0)
	anim.play("idle")
	add_child(anim)
	sprite = anim

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
