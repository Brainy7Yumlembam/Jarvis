package co.aura.presentation.component

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 3D vector coordinate in normalized model space [-1, 1].
 */
@Immutable
data class Point3D(val x: Float, val y: Float, val z: Float) {
    fun rotate(pitch: Float, yaw: Float, roll: Float): Point3D {
        val cX = cos(pitch); val sX = sin(pitch)
        val y1 = y * cX - z * sX
        val z1 = y * sX + z * cX

        val cY = cos(yaw); val sY = sin(yaw)
        val x2 = x * cY + z1 * sY
        val z2 = -x * sY + z1 * cY

        val cZ = cos(roll); val sZ = sin(roll)
        val x3 = x2 * cZ - y1 * sZ
        val y3 = x2 * sZ + y1 * cZ

        return Point3D(x3, y3, z2)
    }

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun distanceTo(other: Point3D): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun project(centerX: Float, centerY: Float, cameraDist: Float): ProjectedPoint {
        val safeZ = (cameraDist + z).coerceAtLeast(cameraDist * 0.15f)
        val k = cameraDist / safeZ
        return ProjectedPoint(
            screenX = centerX + x * k,
            screenY = centerY + y * k,
            depth = z,
            scale = k
        )
    }
}

@Immutable
data class ProjectedPoint(
    val screenX: Float,
    val screenY: Float,
    val depth: Float,
    val scale: Float
)

/**
 * Classification of particles in the controlled-chaos holographic AI core.
 */
enum class ParticleKind {
    MICRO_DUST,    // Fine ambient particles filling the 3D volume
    ORBITAL_BEAD,  // Nodes aligned along curved 3D orbital streams
    CORE_NUCLEUS,  // Concentrated central turbulent plasma nodes
    HOT_SPARK,     // Rare, intensely bright energy spark
    OUTER_TANGENT  // Drifting energy particles escaping along curved tangents
}

/**
 * An individual 3D particle in the holographic AI matrix.
 */
@Immutable
data class NeuralParticle(
    val id: Int,
    val basePos: Point3D,
    val kind: ParticleKind,
    val streamId: Int, // Orbital stream association (-1 if unattached)
    val baseRadius: Float,
    val baseAlpha: Float,
    val baseBrightness: Float,
    val pulsePhase: Float,
    val radialDistance: Float,
    val displacementBias: Float // Responsiveness to speech waves
)

/**
 * A selective technological filament connecting two particles.
 */
@Immutable
data class SynapticFilament(
    val nodeA: Int,
    val nodeB: Int,
    val restLength: Float,
    val restingAlpha: Float,
    val isStreamBond: Boolean, // True if connecting adjacent points on an orbital path
    val isRadialSpoke: Boolean // True if radiating outward from core nucleus
)

/**
 * A 3D curved orbital stream defining flowing bands of energy across the sphere.
 */
@Immutable
data class OrbitalStream(
    val id: Int,
    val tiltX: Float,
    val tiltY: Float,
    val tiltZ: Float,
    val radiusRatio: Float,
    val eccentricity: Float,
    val rotationSpeed: Float,
    val arcSpan: Float, // Fraction of 2*PI covered
    val arcOffset: Float
)

/**
 * Complete precomputed topological network of JARVIS's holographic AI Core,
 * modeled directly on the Avengers visual reference.
 */
@Immutable
class NeuralBrainTopology(
    val particles: List<NeuralParticle>,
    val filaments: List<SynapticFilament>,
    val streams: List<OrbitalStream>,
    val qualityLevel: Int
) {
    companion object {
        /**
         * Generates the authentic Avengers JARVIS holographic AI Core topology
         * with controlled visual chaos, varied density, and rich 3D volumetric depth.
         */
        fun generate(targetCount: Int = 2600): NeuralBrainTopology {
            val rng = Random(42) // Stable, deterministic seed
            val particles = ArrayList<NeuralParticle>(targetCount)

            // 1. Define 10 distinct 3D Curved Orbital Streams (diverse inclinations & eccentricities)
            val streams = listOf(
                OrbitalStream(0, 0.22f, 0.05f, 0.08f, 0.94f, 1.02f, 0.25f, 1.0f, 0f), // Main equatorial band
                OrbitalStream(1, 0.75f, 0.45f, 0.15f, 0.88f, 1.08f, -0.32f, 0.85f, 0.4f), // Tilted oblique stream A
                OrbitalStream(2, 1.25f, -0.55f, 0.35f, 0.82f, 1.05f, 0.28f, 0.90f, 1.2f), // Tilted oblique stream B
                OrbitalStream(3, 1.52f, 0.12f, 0.85f, 0.90f, 1.04f, -0.22f, 0.75f, 2.1f), // Polar-tilted stream
                OrbitalStream(4, 0.45f, -0.80f, 0.50f, 0.72f, 1.12f, 0.38f, 0.95f, 0.8f), // Mid-inner inclined stream
                OrbitalStream(5, -0.85f, 0.35f, -0.40f, 0.65f, 1.15f, -0.40f, 0.80f, 2.5f), // Inner counter-rotating stream
                OrbitalStream(6, 0.95f, 0.95f, -0.20f, 0.98f, 1.06f, 0.18f, 0.70f, 1.7f), // Outer cortical stream A
                OrbitalStream(7, -1.35f, -0.25f, 0.60f, 1.02f, 1.10f, -0.26f, 0.65f, 3.2f), // Outer cortical stream B
                OrbitalStream(8, 0.15f, 1.45f, 0.25f, 0.48f, 1.18f, 0.48f, 1.0f, 0.2f), // Tight nucleus swirl A
                OrbitalStream(9, -0.40f, -1.25f, -0.65f, 0.42f, 1.22f, -0.52f, 0.90f, 1.9f)  // Tight nucleus swirl B
            )

            var pId = 0

            // 2. Distribute particles along the Orbital Streams (~40% of particles)
            val streamParticleCount = (targetCount * 0.40f).toInt()
            for (i in 0 until streamParticleCount) {
                val stream = streams[i % streams.size]
                val tFraction = rng.nextFloat()
                val angle = stream.arcOffset + tFraction * stream.arcSpan * 2f * PI.toFloat()

                // Elliptical radius with slight organic variation
                val r = stream.radiusRatio * (1f + (stream.eccentricity - 1f) * cos(angle)) * (0.95f + rng.nextFloat() * 0.10f)

                // 3D point on stream plane
                val rawX = r * cos(angle)
                val rawY = r * sin(angle)
                val rawZ = (rng.nextFloat() - 0.5f) * 0.08f // Small thickness around the path

                // Rotate by stream's 3D orientation
                val p3D = Point3D(rawX, rawY, rawZ).rotate(stream.tiltX, stream.tiltY, stream.tiltZ)

                val isHot = rng.nextFloat() < 0.08f
                val kind = if (isHot) ParticleKind.HOT_SPARK else ParticleKind.ORBITAL_BEAD
                val baseRadius = if (isHot) 2.6f + rng.nextFloat() * 1.6f else 1.3f + rng.nextFloat() * 1.2f
                val alpha = if (isHot) 0.95f else 0.55f + rng.nextFloat() * 0.35f
                val brightness = if (isHot) 1.5f else 0.85f + rng.nextFloat() * 0.35f

                particles.add(
                    NeuralParticle(
                        id = pId++,
                        basePos = p3D,
                        kind = kind,
                        streamId = stream.id,
                        baseRadius = baseRadius,
                        baseAlpha = alpha,
                        baseBrightness = brightness,
                        pulsePhase = rng.nextFloat() * 2f * PI.toFloat(),
                        radialDistance = p3D.length(),
                        displacementBias = 0.8f + rng.nextFloat() * 0.4f
                    )
                )
            }

            // 3. Generate Central Turbulent Nucleus (~18% of particles)
            // Dense, bright, radiating energy concentration in the core
            val nucleusCount = (targetCount * 0.18f).toInt()
            repeat(nucleusCount) {
                val r = 0.04f + rng.nextFloat().let { it * it } * 0.34f // Dense concentration toward center
                val theta = rng.nextFloat() * 2f * PI.toFloat()
                val phi = (rng.nextFloat() - 0.5f) * PI.toFloat()

                // Spiral radial distortion (swirling vortex)
                val spiralAngle = theta + r * 6.5f
                val px = r * cos(phi) * cos(spiralAngle)
                val py = r * sin(phi) * 0.92f
                val pz = r * cos(phi) * sin(spiralAngle)

                val p3D = Point3D(px, py, pz)
                val isHotCore = r < 0.18f || rng.nextFloat() < 0.14f

                particles.add(
                    NeuralParticle(
                        id = pId++,
                        basePos = p3D,
                        kind = if (isHotCore) ParticleKind.HOT_SPARK else ParticleKind.CORE_NUCLEUS,
                        streamId = -1,
                        baseRadius = if (isHotCore) 2.8f + rng.nextFloat() * 1.8f else 1.4f + rng.nextFloat() * 1.2f,
                        baseAlpha = if (isHotCore) 0.98f else 0.70f + rng.nextFloat() * 0.25f,
                        baseBrightness = if (isHotCore) 1.8f else 1.1f,
                        pulsePhase = rng.nextFloat() * 2f * PI.toFloat(),
                        radialDistance = p3D.length(),
                        displacementBias = 0.5f + rng.nextFloat() * 0.3f
                    )
                )
            }

            // 4. Volumetric Spherical Infill with Controlled Density Clusters (~34% of particles)
            // Creates organic clusters, voids, and non-uniform spherical depth
            val infillCount = (targetCount * 0.34f).toInt()
            val clusterCenters = listOf(
                Point3D(0.35f, 0.25f, -0.30f),
                Point3D(-0.40f, -0.20f, 0.35f),
                Point3D(0.20f, -0.45f, 0.25f),
                Point3D(-0.30f, 0.40f, -0.20f),
                Point3D(0.45f, -0.15f, -0.40f),
                Point3D(-0.25f, -0.35f, -0.35f)
            )

            repeat(infillCount) {
                // Bias 50% toward irregular cluster centers, 50% across spherical volume
                val p3D = if (rng.nextFloat() < 0.52f) {
                    val cluster = clusterCenters[rng.nextInt(clusterCenters.size)]
                    val offsetR = rng.nextFloat() * 0.28f
                    val th = rng.nextFloat() * 2f * PI.toFloat()
                    val ph = (rng.nextFloat() - 0.5f) * PI.toFloat()
                    Point3D(
                        cluster.x + offsetR * cos(ph) * cos(th),
                        cluster.y + offsetR * sin(ph),
                        cluster.z + offsetR * cos(ph) * sin(th)
                    )
                } else {
                    // Spherical shell layer (r: 0.35 to 1.05)
                    val r = 0.35f + rng.nextFloat() * 0.68f
                    val th = rng.nextFloat() * 2f * PI.toFloat()
                    val cosPh = 2f * rng.nextFloat() - 1f
                    val sinPh = sqrt((1f - cosPh * cosPh).coerceAtLeast(0f))
                    Point3D(r * sinPh * cos(th), r * sinPh * sin(th) * 0.95f, r * cosPh)
                }

                val dist = p3D.length()
                val isSpark = rng.nextFloat() < 0.05f

                particles.add(
                    NeuralParticle(
                        id = pId++,
                        basePos = p3D,
                        kind = if (isSpark) ParticleKind.HOT_SPARK else ParticleKind.MICRO_DUST,
                        streamId = -1,
                        baseRadius = if (isSpark) 2.4f + rng.nextFloat() * 1.4f else 0.9f + rng.nextFloat() * 1.1f,
                        baseAlpha = if (isSpark) 0.92f else 0.35f + rng.nextFloat() * 0.45f,
                        baseBrightness = if (isSpark) 1.4f else 0.65f + rng.nextFloat() * 0.35f,
                        pulsePhase = rng.nextFloat() * 2f * PI.toFloat(),
                        radialDistance = dist,
                        displacementBias = 1.0f + rng.nextFloat() * 0.4f
                    )
                )
            }

            // 5. Outer Drifting Tangent Particles (~8% of particles)
            // Asymmetrically escapes the sphere along curved arcs, matching reference
            val outerCount = targetCount - particles.size
            repeat(outerCount) {
                val r = 1.05f + rng.nextFloat() * 0.38f
                val th = rng.nextFloat() * 2f * PI.toFloat()
                val ph = (rng.nextFloat() - 0.5f) * PI.toFloat()
                val p3D = Point3D(r * cos(ph) * cos(th), r * sin(ph) * 0.95f, r * cos(ph) * sin(th))

                particles.add(
                    NeuralParticle(
                        id = pId++,
                        basePos = p3D,
                        kind = ParticleKind.OUTER_TANGENT,
                        streamId = -1,
                        baseRadius = 0.8f + rng.nextFloat() * 1.0f,
                        baseAlpha = 0.20f + rng.nextFloat() * 0.40f,
                        baseBrightness = 0.55f + rng.nextFloat() * 0.45f,
                        pulsePhase = rng.nextFloat() * 2f * PI.toFloat(),
                        radialDistance = r,
                        displacementBias = 1.2f + rng.nextFloat() * 0.5f
                    )
                )
            }

            // 6. Precompute Technological & Neural Filaments (~1,400 connections)
            val filaments = ArrayList<SynapticFilament>(1600)
            val maxFilaments = 1500

            // A. Intra-stream connections (links particles along the same orbital streams -> curved arcs)
            val particlesByStream = particles.filter { it.streamId >= 0 }.groupBy { it.streamId }
            for ((_, streamParts) in particlesByStream) {
                if (filaments.size >= maxFilaments) break
                val sorted = streamParts.sortedBy { it.basePos.x + it.basePos.y * 0.5f }
                for (k in 0 until sorted.size - 1) {
                    if (filaments.size >= maxFilaments) break
                    val p1 = sorted[k]
                    val p2 = sorted[k + 1]
                    val d = p1.basePos.distanceTo(p2.basePos)
                    if (d in 0.02f..0.15f) {
                        filaments.add(
                            SynapticFilament(
                                nodeA = p1.id,
                                nodeB = p2.id,
                                restLength = d,
                                restingAlpha = (0.35f + rng.nextFloat() * 0.45f),
                                isStreamBond = true,
                                isRadialSpoke = false
                            )
                        )
                    }
                }
            }

            // B. Radial Spoke Filaments (Radiating outward from nucleus to mid-shell, like rays in image)
            val nucleusParticles = particles.filter { it.kind == ParticleKind.CORE_NUCLEUS || it.radialDistance < 0.30f }
            val midParticles = particles.filter { it.radialDistance in 0.45f..0.85f }
            val sampleCount = 180.coerceAtMost(nucleusParticles.size)

            for (m in 0 until sampleCount) {
                if (filaments.size >= maxFilaments) break
                val nPart = nucleusParticles[m % nucleusParticles.size]
                // Find nearest mid particle
                var nearest: NeuralParticle? = null
                var nearestDist = Float.MAX_VALUE
                for (p in midParticles) {
                    val d = nPart.basePos.distanceTo(p.basePos)
                    if (d in 0.20f..0.55f && d < nearestDist) {
                        nearestDist = d
                        nearest = p
                    }
                }
                if (nearest != null) {
                    filaments.add(
                        SynapticFilament(
                            nodeA = nPart.id,
                            nodeB = nearest.id,
                            restLength = nearestDist,
                            restingAlpha = 0.25f + rng.nextFloat() * 0.35f,
                            isStreamBond = false,
                            isRadialSpoke = true
                        )
                    )
                }
            }

            // C. Inter-stream synapses (linking close particles between crossing orbital planes)
            val connectionCounts = IntArray(particles.size)
            for (p in particles) {
                if (filaments.size >= maxFilaments) break
                if (p.kind == ParticleKind.OUTER_TANGENT || connectionCounts[p.id] >= 3) continue

                for (candId in (p.id + 1) until (p.id + 35).coerceAtMost(particles.size)) {
                    if (filaments.size >= maxFilaments) break
                    val p2 = particles[candId]
                    if (p.streamId != -1 && p.streamId == p2.streamId) continue // Already handled
                    if (connectionCounts[p2.id] >= 3) continue

                    val d = p.basePos.distanceTo(p2.basePos)
                    if (d in 0.035f..0.12f) {
                        filaments.add(
                            SynapticFilament(
                                nodeA = p.id,
                                nodeB = p2.id,
                                restLength = d,
                                restingAlpha = 0.18f + rng.nextFloat() * 0.30f,
                                isStreamBond = false,
                                isRadialSpoke = false
                            )
                        )
                        connectionCounts[p.id]++
                        connectionCounts[p2.id]++
                    }
                }
            }

            return NeuralBrainTopology(
                particles = particles,
                filaments = filaments,
                streams = streams,
                qualityLevel = targetCount
            )
        }
    }
}
