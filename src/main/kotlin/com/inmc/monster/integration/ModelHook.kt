package com.inmc.monster.integration

import kr.inmc.core.integration.PluginClasses
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import java.util.logging.Logger

/**
 * Attaches a custom model to a mob, through whichever model plugin the server actually runs.
 *
 * Two providers are supported and probed in order. They are not interchangeable at the API
 * level - BetterModel and ModelEngine share no types - so each gets its own reflective
 * implementation behind [ModelProvider], and the rest of the plugin only ever sees this class.
 *
 * BetterModel is tried first when both are present. That is not a quality judgement so much as
 * an availability one: BetterModel's API could be verified against the jar on this server, and
 * a verified path should win over an unverified one.
 */
class ModelHook(private val logger: Logger) {

    /** What a model plugin has to be able to do for us. */
    private interface ModelProvider {
        val name: String
        fun apply(entity: Entity, modelId: String, hideBase: Boolean): Boolean
        fun remove(entity: Entity)

        /** Known model ids, for GUI validation. Empty when the provider cannot enumerate them. */
        fun modelIds(): Set<String> = emptySet()
    }

    private var provider: ModelProvider? = null

    val isEnabled: Boolean get() = provider != null

    /** Name of the plugin currently supplying models, for status screens. */
    val providerName: String get() = provider?.name ?: "없음"

    fun setup() {
        provider = null

        if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
            provider = tryBetterModel()
            if (provider != null) {
                logger.info("BetterModel 연동 활성화")
                return
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) {
            provider = tryModelEngine()
            if (provider != null) {
                logger.info("ModelEngine 연동 활성화 (R4)")
                return
            }
        }
        logger.info("모델 플러그인 없음 - 모델 설정은 무시되고 바닐라 외형으로 표시됩니다")
    }

    /**
     * Attaches [modelId] to [entity]. Returns true when a model was actually applied.
     *
     * A false result is not an error at the call site - the mob simply keeps its vanilla look -
     * but it is worth reporting, because model mobs have their own spawn ceiling and one that
     * failed to render should not be counted against it.
     */
    fun applyModel(entity: Entity, modelId: String, hideBaseEntity: Boolean = true): Boolean {
        val active = provider ?: return false
        if (modelId.isBlank()) return false
        return try {
            active.apply(entity, modelId, hideBaseEntity)
        } catch (t: Throwable) {
            logger.warning(active.name + " 모델 적용 실패 (" + modelId + "): " + t.message)
            false
        }
    }

    /** Swaps the model on an entity that already has one - used for phase transitions. */
    fun swapModel(entity: Entity, modelId: String): Boolean {
        if (provider == null || modelId.isBlank()) return false
        removeModel(entity)
        return applyModel(entity, modelId)
    }

    fun removeModel(entity: Entity) {
        val active = provider ?: return
        try {
            active.remove(entity)
        } catch (_: Throwable) {
            // The entity may already be gone; there is nothing left to clean up.
        }
    }

    /** Model ids the provider knows about. Empty means "cannot enumerate", not "none exist". */
    fun knownModels(): Set<String> = provider?.modelIds() ?: emptySet()

    /**
     * True when [modelId] is definitely wrong.
     *
     * Only answers false-positively when the provider can enumerate its models; otherwise it
     * says nothing, because refusing an id we simply cannot check would block a valid model.
     */
    fun isUnknownModel(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        val known = knownModels()
        if (known.isEmpty()) return false
        return known.none { it.equals(modelId, ignoreCase = true) }
    }

    // --- BetterModel -----------------------------------------------------------

    /**
     * BetterModel 3.x.
     *
     * Verified against `bettermodel-3.4.1-paper.jar`. The flow is:
     * `BetterModel.modelOrNull(id)` -> `ModelRenderer#getOrCreate(PlatformEntity)` -> an
     * `EntityTracker`, with `BukkitAdapter.adapt(Entity)` bridging Bukkit to BetterModel's
     * platform types. Removal goes through the per-entity `EntityTrackerRegistry`.
     */
    private fun tryBetterModel(): ModelProvider? = try {
        val api = PluginClasses.require("BetterModel", "kr.toxicity.model.api.BetterModel")
        val modelOrNull = api.getMethod("modelOrNull", String::class.java)
        val modelKeys = api.methods.firstOrNull { it.name == "modelKeys" && it.parameterCount == 0 }
        val registryOrNull = api.methods.first {
            it.name == "registryOrNull" && it.parameterCount == 1 &&
                it.parameterTypes[0] == java.util.UUID::class.java
        }

        val adapter = PluginClasses.require("BetterModel", "kr.toxicity.model.api.bukkit.platform.BukkitAdapter")
        val platformEntityClass = PluginClasses.require("BetterModel", "kr.toxicity.model.api.platform.PlatformEntity")
        val adapt = adapter.methods.first {
            it.name == "adapt" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Entity::class.java
        }

        val rendererClass = PluginClasses.require("BetterModel", "kr.toxicity.model.api.data.renderer.ModelRenderer")
        val getOrCreate = rendererClass.methods.first {
            it.name == "getOrCreate" && it.parameterCount == 1 &&
                it.parameterTypes[0] == platformEntityClass
        }

        val trackerClass = PluginClasses.require("BetterModel", "kr.toxicity.model.api.tracker.EntityTracker")
        val hideOption = trackerClass.methods.firstOrNull {
            it.name == "hideOption" && it.parameterCount == 1
        }
        val hideOptionClass = PluginClasses.find("BetterModel", "kr.toxicity.model.api.tracker.EntityHideOption")
        val hideDefault = hideOptionClass?.getField("DEFAULT")?.get(null)

        val registryClass = PluginClasses.require("BetterModel", "kr.toxicity.model.api.tracker.EntityTrackerRegistry")
        val close = registryClass.methods.first { it.name == "close" && it.parameterCount == 0 }

        object : ModelProvider {
            override val name = "BetterModel"

            override fun apply(entity: Entity, modelId: String, hideBase: Boolean): Boolean {
                val renderer = modelOrNull.invoke(null, modelId)
                if (renderer == null) {
                    logger.warning("BetterModel 모델을 찾을 수 없습니다: " + modelId)
                    return false
                }
                val platformEntity = adapt.invoke(null, entity) ?: return false
                val tracker = getOrCreate.invoke(renderer, platformEntity) ?: return false
                // DEFAULT hides the vanilla body parts the model replaces while leaving the
                // entity itself as the hitbox and AI driver.
                if (hideBase && hideOption != null && hideDefault != null) {
                    hideOption.invoke(tracker, hideDefault)
                }
                return true
            }

            override fun remove(entity: Entity) {
                val registry = registryOrNull.invoke(null, entity.uniqueId) ?: return
                close.invoke(registry)
            }

            override fun modelIds(): Set<String> = try {
                @Suppress("UNCHECKED_CAST")
                (modelKeys?.invoke(null) as? Set<String>) ?: emptySet()
            } catch (_: Throwable) {
                emptySet()
            }
        }
    } catch (t: Throwable) {
        logger.warning(
            "BetterModel 연동 실패 (버전 불일치일 수 있습니다): " +
                t.javaClass.simpleName + ": " + t.message
        )
        null
    }

    // --- ModelEngine -----------------------------------------------------------

    /**
     * ModelEngine R4.
     *
     * R3 and R4 are not source-compatible and this targets R4. Unlike the BetterModel path this
     * could not be verified against a jar - ModelEngine is not installed here - so it is written
     * to fail loudly at setup rather than silently at spawn, and it is only reached when
     * BetterModel is absent.
     */
    private fun tryModelEngine(): ModelProvider? = try {
        val api = PluginClasses.require("ModelEngine", "com.ticxo.modelengine.api.ModelEngineAPI")

        val createModeledEntity = api.methods.firstOrNull {
            it.name == "createModeledEntity" && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(Entity::class.java)
        } ?: error("createModeledEntity(Entity) 를 찾을 수 없습니다")

        val createActiveModel = api.methods.firstOrNull {
            it.name == "createActiveModel" && it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: error("createActiveModel(String) 를 찾을 수 없습니다")

        val getModeledEntity = api.methods.firstOrNull {
            it.name == "getModeledEntity" && it.parameterCount == 1
        }

        val modeledEntityClass = createModeledEntity.returnType
        val addModel = modeledEntityClass.methods.firstOrNull {
            it.name == "addModel" && it.parameterCount == 2
        } ?: modeledEntityClass.methods.firstOrNull {
            it.name == "addModel" && it.parameterCount == 1
        } ?: error("addModel 을 찾을 수 없습니다")

        val setBaseEntityVisible = modeledEntityClass.methods.firstOrNull {
            it.name == "setBaseEntityVisible" && it.parameterCount == 1
        }
        val destroy = modeledEntityClass.methods.firstOrNull {
            (it.name == "destroy" || it.name == "remove") && it.parameterCount == 0
        }

        object : ModelProvider {
            override val name = "ModelEngine"

            override fun apply(entity: Entity, modelId: String, hideBase: Boolean): Boolean {
                val model = createActiveModel.invoke(null, modelId)
                if (model == null) {
                    logger.warning("ModelEngine 모델을 찾을 수 없습니다: " + modelId)
                    return false
                }
                val modeled = createModeledEntity.invoke(null, entity) ?: return false
                if (addModel.parameterCount == 2) addModel.invoke(modeled, model, true)
                else addModel.invoke(modeled, model)
                if (hideBase) setBaseEntityVisible?.invoke(modeled, false)
                return true
            }

            override fun remove(entity: Entity) {
                val modeled = getModeledEntity?.let { method ->
                    runCatching { method.invoke(null, entity) }.getOrNull()
                        ?: runCatching { method.invoke(null, entity.uniqueId) }.getOrNull()
                } ?: return
                destroy?.invoke(modeled)
            }
        }
    } catch (t: Throwable) {
        logger.warning(
            "ModelEngine 연동 실패 - R4 가 맞는지 확인해주세요 (R3 는 API 가 다릅니다): " +
                t.javaClass.simpleName + ": " + t.message
        )
        null
    }
}
