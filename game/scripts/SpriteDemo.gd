extends Node2D

# Écran de comparaison : montre le héros M dans deux résolutions
# pour aider à choisir le format final.

signal back_pressed

const HERO_PATH := "res://assets/sprites/hero_m/source.png"

func _ready() -> void:
	var bg := ColorRect.new()
	bg.color = Color(0.10, 0.07, 0.14)
	bg.size = Vector2(720, 1280)
	add_child(bg)

	var title := Label.new()
	title.text = "Comparaison de tailles - Héros M"
	title.position = Vector2(0, 60)
	title.size = Vector2(720, 40)
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	title.add_theme_font_size_override("font_size", 26)
	title.add_theme_color_override("font_color", Color(0.95, 0.95, 1.0))
	add_child(title)

	var sub := Label.new()
	sub.text = "Même sprite, deux échelles dans le contexte du jeu"
	sub.position = Vector2(0, 100)
	sub.size = Vector2(720, 20)
	sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	sub.add_theme_font_size_override("font_size", 14)
	sub.add_theme_color_override("font_color", Color(0.7, 0.7, 0.85))
	add_child(sub)

	var tex: Texture2D = null
	if ResourceLoader.exists(HERO_PATH):
		tex = load(HERO_PATH)

	# Bloc HD : ~180 px de hauteur, grille 3x2 cells de 200x190
	_build_demo("HD pixel art (~180 px)", Vector2(40, 150), 180.0, tex, 200, 190, 3, 2)

	# Bloc Mid : ~80 px de hauteur, grille 5x4 cells de 120x100 (taille actuelle du jeu)
	_build_demo("Mid (~80 px) - taille actuelle", Vector2(40, 720), 80.0, tex, 120, 100, 5, 4)

	if tex == null:
		var warn := Label.new()
		warn.text = "⚠ source.png introuvable dans assets/sprites/hero_m/"
		warn.position = Vector2(0, 1170)
		warn.size = Vector2(720, 30)
		warn.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		warn.add_theme_color_override("font_color", Color(1, 0.5, 0.5))
		add_child(warn)

	var back := Button.new()
	back.text = "← Retour à la carte"
	back.position = Vector2(20, 1220)
	back.size = Vector2(220, 50)
	back.add_theme_font_size_override("font_size", 18)
	back.pressed.connect(func() -> void: emit_signal("back_pressed"))
	add_child(back)

func _build_demo(label_text: String, top_left: Vector2, hero_h: float,
		tex: Texture2D, cell_w: int, cell_h: int, cols: int, rows: int) -> void:
	var lbl := Label.new()
	lbl.text = label_text
	lbl.position = top_left
	lbl.size = Vector2(640, 28)
	lbl.add_theme_font_size_override("font_size", 20)
	lbl.add_theme_color_override("font_color", Color(0.95, 0.85, 0.55))
	add_child(lbl)

	var grid_origin := top_left + Vector2(20, 40)
	var hero_placed_in_cell := Vector2i(0, rows - 1)
	var enemy_cell := Vector2i(cols - 1, rows - 1)

	for r in range(rows):
		for c in range(cols):
			var cell_pos: Vector2 = grid_origin + Vector2(c * (cell_w + 4), r * (cell_h + 4))
			var cell := ColorRect.new()
			cell.color = Color(0.25, 0.22, 0.20, 0.55)
			cell.position = cell_pos
			cell.size = Vector2(cell_w, cell_h)
			add_child(cell)

			# Bordure subtile
			var border := ReferenceRect.new()
			border.position = cell_pos
			border.size = Vector2(cell_w, cell_h)
			border.border_color = Color(0.4, 0.35, 0.30, 0.7)
			border.editor_only = false
			add_child(border)

			# Place le héros dans la cellule choisie
			if Vector2i(c, r) == hero_placed_in_cell and tex != null:
				var sprite := Sprite2D.new()
				sprite.texture = tex
				var s: float = hero_h / float(tex.get_height())
				sprite.scale = Vector2(s, s)
				sprite.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
				# Pieds du sprite collés au bas de la cellule
				sprite.position = cell_pos + Vector2(cell_w * 0.5, cell_h - hero_h * 0.5 - 4)
				add_child(sprite)

				var tag := Label.new()
				tag.text = "Héros"
				tag.position = cell_pos + Vector2(0, -18)
				tag.size = Vector2(cell_w, 16)
				tag.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
				tag.add_theme_font_size_override("font_size", 11)
				tag.add_theme_color_override("font_color", Color(0.7, 0.9, 1.0))
				add_child(tag)

			# Place un ennemi factice (slime vert) pour l'échelle
			if Vector2i(c, r) == enemy_cell:
				var enemy_size: float = hero_h * 0.55
				var enemy := ColorRect.new()
				enemy.color = Color(0.45, 0.95, 0.50)
				enemy.size = Vector2(enemy_size, enemy_size)
				enemy.position = cell_pos + Vector2(
					(cell_w - enemy_size) * 0.5,
					cell_h - enemy_size - 6
				)
				add_child(enemy)

				var etag := Label.new()
				etag.text = "Slime"
				etag.position = cell_pos + Vector2(0, -18)
				etag.size = Vector2(cell_w, 16)
				etag.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
				etag.add_theme_font_size_override("font_size", 11)
				etag.add_theme_color_override("font_color", Color(0.7, 1.0, 0.7))
				add_child(etag)

	var info := Label.new()
	info.text = "Cellule : %d × %d px   |   Grille : %d × %d   |   Hauteur héros : %d px" % [
		cell_w, cell_h, cols, rows, int(hero_h)
	]
	info.position = top_left + Vector2(20, 40 + rows * (cell_h + 4) + 6)
	info.size = Vector2(640, 18)
	info.add_theme_font_size_override("font_size", 12)
	info.add_theme_color_override("font_color", Color(0.65, 0.65, 0.75))
	add_child(info)
