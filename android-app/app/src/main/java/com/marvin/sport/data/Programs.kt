package com.marvin.sport.data

object Programs {
    val strength: TrainingProgram = StrengthProgramBuilder.build()
    val striking: TrainingProgram = StrikingProgramBuilder.build()
    val grappling: TrainingProgram = GrapplingProgramBuilder.build()
    val home: TrainingProgram = HomeProgramBuilder.build()
    val park: TrainingProgram = ParkProgramBuilder.build()

    val all: List<TrainingProgram> = listOf(strength, striking, grappling, home, park)

    fun byId(id: String): TrainingProgram =
        all.firstOrNull { it.id == id } ?: strength
}
