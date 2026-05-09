extends CanvasLayer

# Barre du haut : étage, or, vague.

var label_floor: Label
var label_gold: Label
var label_max: Label

func _ready() -> void:
	var bar := ColorRect.new()
	bar.color = Color(0.06, 0.05, 0.10, 0.92)
	bar.position = Vector2(0, 0)
	bar.size = Vector2(720, 56)
	add_child(bar)

	label_floor = Label.new()
	label_floor.position = Vector2(20, 14)
	label_floor.add_theme_font_size_override("font_size", 22)
	label_floor.add_theme_color_override("font_color", Color(1, 1, 1))
	add_child(label_floor)

	label_max = Label.new()
	label_max.position = Vector2(220, 14)
	label_max.add_theme_font_size_override("font_size", 18)
	label_max.add_theme_color_override("font_color", Color(0.7, 0.7, 0.8))
	add_child(label_max)

	label_gold = Label.new()
	label_gold.position = Vector2(540, 14)
	label_gold.add_theme_font_size_override("font_size", 22)
	label_gold.add_theme_color_override("font_color", Color(1.0, 0.85, 0.25))
	add_child(label_gold)

	refresh()

func refresh() -> void:
	if not is_inside_tree():
		return
	label_floor.text = "Étage %d" % GameState.current_floor
	label_max.text = "(max %d)" % GameState.max_floor_reached
	label_gold.text = "Or %d" % GameState.gold

func _process(_delta: float) -> void:
	refresh()
