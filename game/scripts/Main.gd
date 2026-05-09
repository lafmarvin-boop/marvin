extends Node2D

# Contrôleur racine : alterne entre la carte du donjon et les combats.

var current_screen: Node = null
var hud: CanvasLayer = null

func _ready() -> void:
	hud = load("res://scripts/HUD.gd").new()
	add_child(hud)
	show_dungeon_map()

func _clear_screen() -> void:
	if current_screen and is_instance_valid(current_screen):
		current_screen.queue_free()
	current_screen = null

func show_dungeon_map() -> void:
	_clear_screen()
	var map: Node2D = load("res://scripts/DungeonMap.gd").new()
	add_child(map)
	current_screen = map
	map.battle_requested.connect(_on_battle_requested)
	if hud:
		hud.refresh()

func _on_battle_requested(floor: int) -> void:
	GameState.current_floor = floor
	_clear_screen()
	var battle: Node2D = load("res://scripts/Battle.gd").new()
	add_child(battle)
	current_screen = battle
	battle.battle_finished.connect(_on_battle_finished)
	if hud:
		hud.refresh()

func _on_battle_finished(victory: bool) -> void:
	if victory:
		GameState.current_floor += 1
		if GameState.current_floor > GameState.max_floor_reached:
			GameState.max_floor_reached = GameState.current_floor
	show_dungeon_map()
