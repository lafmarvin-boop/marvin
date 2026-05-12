package com.marvin.sport.data

/** Registre central des programmes disponibles dans l'application. */
object Programs {
    val strength: TrainingProgram = StrengthProgramBuilder.build()
    val striking: TrainingProgram = StrikingProgramBuilder.build()
    val grappling: TrainingProgram = GrapplingProgramBuilder.build()

    val all: List<TrainingProgram> = listOf(strength, striking, grappling)

    fun byId(id: String): TrainingProgram =
        all.firstOrNull { it.id == id } ?: strength
}
