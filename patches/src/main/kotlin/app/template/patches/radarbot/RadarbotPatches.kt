package app.morphe.patches.radarbot

// NON COMPILE, NON TESTE : ecrit d'apres la doc/les exemples Morphe.
// Verifie les imports et la forme de Compatibility/AppTarget en copiant
// celle du Constants.kt de morphe-patches-template.

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.w3c.dom.Element

object Constants {
    val COMPATIBILITY_RADARBOT = Compatibility(
        name = "Radarbot",
        packageName = "com.vialsoft.radarbot_free",
        targets = listOf(AppTarget(version = "9.35.26")),
    )
}

// ---------------------------------------------------------------------------
// Fingerprints
// ---------------------------------------------------------------------------

// CarService.createHostValidator() : le nom de la classe et de la methode ne sont
// pas obfusques (service declare dans le manifest, methode de CarAppService).
// Le type de retour (Lav3; dans 9.35.26) est obfusque, donc on ne le fixe pas.
private val createHostValidatorFingerprint = Fingerprint(
    definingClass = "Lcom/vialsoft/radarbot/auto/CarService;",
    name = "createHostValidator",
    parameters = listOf(),
)

// Methode de la bibliotheque Car App qui lit le fichier "car-app-api.level"
// (rw0.j() dans 9.35.26). Ancree sur ses chaines, qui ne sont pas obfusquees.
private val carApiLevelFingerprint = Fingerprint(
    returnType = "I",
    parameters = listOf(),
    strings = listOf(
        "Unrecognized Car API level: ",
        "car-app-api.level",
    ),
)

// ---------------------------------------------------------------------------
// Patch 1 : faire fonctionner Radarbot sur Gearslip
// ---------------------------------------------------------------------------

val radarbotGearslipPatch = bytecodePatch(
    name = "Radarbot Gearslip support",
    description = "Accepte les hotes Android Auto non Google (Gearslip, AARE) et " +
        "corrige le niveau d'API Car App manquant.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_RADARBOT)

    execute {
        var patched = 0

        // 1) createHostValidator() renvoie le validateur "tous les hotes acceptes".
        //    On relit le champ statique de ce type directement dans la methode
        //    d'origine (branche debug : sget-object ...->e:Lav3;) pour ne pas
        //    dependre du nom obfusque.
        val hostMethod = createHostValidatorFingerprint.method
        val validatorType = hostMethod.returnType
        val hostInstructions = hostMethod.implementation!!.instructions

        val sgetIndex = hostInstructions.indexOfFirst { insn ->
            insn.opcode == Opcode.SGET_OBJECT &&
                ((insn as ReferenceInstruction).reference as? FieldReference)
                    ?.type == validatorType
        }
        if (sgetIndex < 0) {
            throw PatchException(
                "[Radarbot Gearslip support] champ allow-all introuvable dans createHostValidator()"
            )
        }
        val allowAll = (hostInstructions[sgetIndex] as ReferenceInstruction).reference as FieldReference

        // Insertion seule en debut de methode, avec un registre deja existant (v0).
        hostMethod.addInstructions(
            0,
            """
                sget-object v0, ${allowAll.definingClass}->${allowAll.name}:${allowAll.type}
                return-object v0
            """,
        )
        patched++

        // 2) Le fichier car-app-api.level est absent de l'APK : on renvoie
        //    directement le niveau 8 (maximum accepte par la bibliotheque).
        carApiLevelFingerprint.method.addInstructions(
            0,
            """
                const/16 v0, 0x8
                return v0
            """,
        )
        patched++

        println("[Radarbot Gearslip support] Successfully applied $patched hooks across target components.")
    }
}

// ---------------------------------------------------------------------------
// Patch 2 : indicateur de vitesse en bas sur l'ecran voiture
// ---------------------------------------------------------------------------

val radarbotSpeedometerBottomPatch = resourcePatch(
    name = "Radarbot car speedometer at bottom",
    description = "Deplace l'indicateur de vitesse de l'ecran voiture en bas a droite, " +
        "au-dessus du nom de la rue.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_RADARBOT)

    execute {
        var patched = 0

        document("res/layout/car_activity_navigation.xml").use { doc ->
            // a) Le Barrier ne doit plus dependre du compteur, sinon le bloc
            //    "controle de section" (place sous le Barrier) passe en bas.
            val barriers = doc.getElementsByTagName("androidx.constraintlayout.widget.Barrier")
            for (i in 0 until barriers.length) {
                val barrier = barriers.item(i) as? Element ?: continue
                if (barrier.getAttribute("app:constraint_referenced_ids").contains("speedometerCar")) {
                    barrier.setAttribute("app:constraint_referenced_ids", "widget_radar")
                    patched++
                }
            }

            // b) Compteur : ancre en bas a droite, au-dessus de roadNameContainer.
            val speedometers = doc.getElementsByTagName("com.vialsoft.radarbot.ui.Speedometer")
            for (i in 0 until speedometers.length) {
                val speedometer = speedometers.item(i) as? Element ?: continue
                if (speedometer.getAttribute("android:id") != "@id/speedometerCar") continue

                speedometer.removeAttribute("app:layout_constraintTop_toTopOf")
                speedometer.setAttribute("app:layout_constraintBottom_toTopOf", "@id/roadNameContainer")
                speedometer.setAttribute("android:layout_marginBottom", "8.0dip")
                patched++
            }
        }

        if (patched != 2) {
            throw PatchException(
                "[Radarbot car speedometer at bottom] $patched modification(s) sur 2 attendues"
            )
        }
        println("[Radarbot car speedometer at bottom] Successfully applied $patched layout changes.")
    }
}
