extends Node2D

# Carte du donjon : progression case par case (style Fissure).

signal battle_requested(floor: int)

const NODE_SPACING := 130
const VISIBLE_BEFORE := 1
const VISIBLE_AFTER := 5

func _ready() -> void:
	var bg := ColorRect.new()
	bg.color = Color(0.10, 0.07, 0.14)
	bg.size = Vector2(720, 1280)
	add_child(bg)

	# Cosmétique : "fissure" verticale au centre
	var crack := Line2D.new()
	crack.default_color = Color(0.9, 0.4, 0.3, 0.35)
	crack.width = 6.0
	crack.add_point(Vector2(360, 70))
	crack.add_point(Vector2(345, 220))
	crack.add_point(Vector2(380, 380))
	crack.add_point(Vector2(340, 540))
	crack.add_point(Vector2(370, 720))
	crack.add_point(Vector2(345, 900))
	crack.add_point(Vector2(380, 1080))
	crack.add_point(Vector2(360, 1260))
	add_child(crack)

	var title := Label.new()
	title.text = "Donjon de la Fissure"
	title.position = Vector2(0, 80)
	title.size = Vector2(720, 50)
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	title.add_theme_font_size_override("font_size", 32)
	title.add_theme_color_override("font_color", Color(0.95, 0.95, 1.0))
	add_child(title)

	var sub := Label.new()
	sub.text = "Choisis ton prochain combat"
	sub.position = Vector2(0, 132)
	sub.size = Vector2(720, 24)
	sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	sub.add_theme_font_size_override("font_size", 16)
	sub.add_theme_color_override("font_color", Color(0.7, 0.7, 0.85))
	add_child(sub)

	var current: int = GameState.current_floor
	var start: int = max(1, current - VISIBLE_BEFORE)
	var y: int = 200
	for f in range(start, current + VISIBLE_AFTER + 1):
		_build_node(f, current, y)
		y += NODE_SPACING

	# Bouton "Réinitialiser progression" (utile en dev)
	var reset := Button.new()
	reset.text = "↺ Reset progression"
	reset.position = Vector2(20, 1220)
	reset.size = Vector2(200, 40)
	reset.add_theme_font_size_override("font_size", 14)
	reset.pressed.connect(_on_reset_pressed)
	add_child(reset)

func _build_node(floor: int, current: int, y: int) -> void:
	var is_boss: bool = (floor % 5 == 0)
	var is_current: bool = (floor == current)
	var is_cleared: bool = (floor < current)
	var is_locked: bool = (floor > current)

	var btn := Button.new()
	var label_txt := "Étage %d" % floor
	if is_boss:
		label_txt = "★ BOSS - Étage %d" % floor
	if is_current:
		label_txt += "  ▶"
	elif is_cleared:
		label_txt += "  ✓"
	elif is_locked:
		label_txt += "  🔒"
	btn.text = label_txt
	btn.position = Vector2(120, y)
	btn.size = Vector2(480, 100)
	btn.add_theme_font_size_override("font_size", 22)

	var sb := StyleBoxFlat.new()
	if is_current:
		sb.bg_color = Color(0.95, 0.55, 0.20) if not is_boss else Color(0.95, 0.30, 0.30)
		sb.border_color = Color(1, 1, 0.6)
		sb.set_border_width_all(3)
	elif is_cleared:
		sb.bg_color = Color(0.25, 0.40, 0.25)
	else:
		sb.bg_color = Color(0.20, 0.18, 0.22)
	sb.set_corner_radius_all(14)
	btn.add_theme_stylebox_override("normal", sb)

	var sb_hover := sb.duplicate() as StyleBoxFlat
	sb_hover.bg_color = sb.bg_color.lightened(0.08)
	btn.add_theme_stylebox_override("hover", sb_hover)
	btn.add_theme_stylebox_override("pressed", sb_hover)

	btn.disabled = not is_current
	if is_current:
		btn.pressed.connect(func() -> void: emit_signal("battle_requested", floor))
	add_child(btn)

func _on_reset_pressed() -> void:
	GameState.current_floor = 1
	GameState.max_floor_reached = 1
	GameState.gold = 100
	GameState.hero_collection.clear()
	GameState.battle_party.clear()
	for id in ["knight", "archer", "mage", "tank"]:
		var inst := GameState.make_hero_instance(id)
		GameState.hero_collection.append(inst)
		GameState.battle_party.append(inst)
	get_tree().reload_current_scene()
