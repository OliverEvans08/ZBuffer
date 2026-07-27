package engine.scene;

import engine.AssetManager;
import engine.EngineContext;
import engine.GameEngine;
import engine.MeshData;
import engine.animation.AnimationClip;
import engine.animation.Animator;
import engine.loop.UpdateScheduler;
import engine.render.Material;
import engine.render.Texture;
import engine.render.Textures;
import engine.spatial.SpatialIndexSynchronizer;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import objects.GameObject;
import objects.MeshObject;
import objects.fixed.Cube;
import objects.fixed.Ground;
import objects.lighting.LightObject;
import util.Vector3;

public final class Scene {

    private static final System.Logger LOGGER = System.getLogger(GameEngine.class.getName());

    private final EngineContext context;
    private final CopyOnWriteArrayList<GameObject> rootObjects;
    private final DerivedObjectCache derivedCache = new DerivedObjectCache();
    private final SpatialIndexSynchronizer spatialIndexes = new SpatialIndexSynchronizer();
    private final SceneGraphUpdater sceneGraphUpdater = new SceneGraphUpdater();
    private final TransformPublisher transformPublisher;
    private final UpdateScheduler updateScheduler;
    private final SceneMutationQueue mutationQueue;

    private UpdateContext[] updateContexts = new UpdateContext[0];

    public Scene(EngineContext context, CopyOnWriteArrayList<GameObject> rootObjects) {
        this.context = context;
        this.rootObjects = rootObjects;

        transformPublisher = new TransformPublisher(
                derivedCache,
                spatialIndexes,
                context.updatePool,
                context.maximumUpdateWorkers
        );

        updateScheduler = new UpdateScheduler(
                context.updatePool,
                context.maximumUpdateWorkers,
                transformPublisher
        );

        mutationQueue = new SceneMutationQueue(
                derivedCache,
                transformPublisher,
                spatialIndexes
        );
    }

    public CopyOnWriteArrayList<GameObject> getRootObjects() {
        return rootObjects;
    }

    public void addRootObject(GameObject object) {
        mutationQueue.add(object);
    }

    public void removeRootObject(GameObject object) {
        mutationQueue.remove(object);
    }

    public void addRootObjectImmediate(GameObject object) {
        if (object == null) {
            return;
        }

        rootObjects.add(object);

        transformPublisher.publishInitialDerivedSnapshot(object);
        spatialIndexes.syncImmediate(object);
    }

    public void queryNearbyCollidersXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        spatialIndexes.queryNearbyCollidersXZ(minX, maxX, minZ, maxZ, output);
    }

    public void queryNearbyRenderablesXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        spatialIndexes.queryNearbyRenderablesXZ(minX, maxX, minZ, maxZ, output);
    }

    public List<GameObject> getColliders() {
        return spatialIndexes.getColliders();
    }

    public void initializeGameObjects() {

        /*
         * ================================================================
         * COMPLETE ENGINE SHOWCASE / TEST SCENE
         * ================================================================
         *
         * Layout:
         *
         *   Z = -12   Lighting room
         *   Z = 10    Material response
         *   Z = 22    Texture and wrapping
         *   Z = 34    Emissive materials and coloured lights
         *   Z = 46    Transform, render and object-state tests
         *   Z = 60    Animation tests
         *   Z = 76    Hierarchy tests
         *   Z = 92+   Imported mesh gallery
         */

        final List<String> meshIds =
                context.assetManager.getMeshIds();

        /*
         * ================================================================
         * SHARED TEXTURES
         * ================================================================
         */

        final Texture fineCheckerTexture =
                Textures.checker(
                        256,
                        256,
                        8,
                        0xFFF1F1F1,
                        0xFF303030
                );

        final Texture mediumCheckerTexture =
                Textures.checker(
                        256,
                        256,
                        32,
                        0xFFE0B25B,
                        0xFF604020
                );

        final Texture largeCheckerTexture =
                Textures.checker(
                        256,
                        256,
                        64,
                        0xFF70A8E8,
                        0xFF172A48
                );

        final Texture whiteGridTexture =
                Textures.grid(
                        256,
                        256,
                        32,
                        0xFFFFFFFF,
                        0xFF555555
                );

        final Texture neonGridTexture =
                Textures.grid(
                        256,
                        256,
                        16,
                        0xFF43E8FF,
                        0xFF07141D
                );

        /*
         * ================================================================
         * SHARED MATERIALS
         * ================================================================
         */

        final Material floorMaterial =
                Material.textured(
                                Textures.grid(
                                        512,
                                        512,
                                        32,
                                        0xFF333840,
                                        0xFF171A1F
                                )
                        )
                        .setTint(
                                new Color(
                                        210,
                                        215,
                                        225
                                )
                        )
                        .setWrap(Texture.Wrap.REPEAT)
                        .setAmbient(0.24)
                        .setDiffuse(0.82);

        final Material wallMaterial =
                Material.textured(
                                Textures.grid(
                                        256,
                                        256,
                                        32,
                                        0xFFBEC3C8,
                                        0xFF92989E
                                )
                        )
                        .setTint(
                                new Color(
                                        225,
                                        225,
                                        220
                                )
                        )
                        .setWrap(Texture.Wrap.REPEAT)
                        .setAmbient(0.18)
                        .setDiffuse(0.88);

        final Material whiteMaterial =
                Material.solid(
                                new Color(
                                        235,
                                        235,
                                        235
                                )
                        )
                        .setAmbient(0.20)
                        .setDiffuse(0.90);

        final Material redMaterial =
                Material.solid(
                                new Color(
                                        220,
                                        55,
                                        45
                                )
                        )
                        .setAmbient(0.18)
                        .setDiffuse(0.92);

        final Material greenMaterial =
                Material.solid(
                                new Color(
                                        60,
                                        205,
                                        105
                                )
                        )
                        .setAmbient(0.18)
                        .setDiffuse(0.92);

        final Material blueMaterial =
                Material.solid(
                                new Color(
                                        55,
                                        105,
                                        230
                                )
                        )
                        .setAmbient(0.18)
                        .setDiffuse(0.92);

        /*
         * ================================================================
         * WORLD FLOOR
         * ================================================================
         */

        final Ground worldFloor =
                new Ground(
                        190.0,
                        60.0
                );

        worldFloor.setName("Showcase world floor");
        worldFloor.setTag("environment");
        worldFloor.setLayer(0);
        worldFloor.setMaterial(floorMaterial);
        worldFloor.setSolid(true);
        worldFloor.setWireframe(false);

        worldFloor.getTransform().position =
                new Vector3(
                        0.0,
                        -0.05,
                        42.0
                );

        addRootObjectImmediate(worldFloor);

        /*
         * Raised centre path.
         */

        for (int pathIndex = 0; pathIndex < 14; pathIndex++) {

            final Cube pathTile =
                    new Cube(
                            1.0,
                            0.0,
                            0.025,
                            -15.0 + pathIndex * 9.0
                    );

            pathTile.setName(
                    "Central path tile " + pathIndex
            );

            pathTile.setTag("path");
            pathTile.setLayer(0);
            pathTile.setSolid(true);

            pathTile.setMaterial(
                    Material.textured(whiteGridTexture)
                            .setTint(
                                    new Color(
                                            150,
                                            155,
                                            165
                                    )
                            )
                            .setWrap(Texture.Wrap.REPEAT)
                            .setAmbient(0.28)
                            .setDiffuse(0.72)
            );

            pathTile.getTransform().scale =
                    new Vector3(
                            3.0,
                            0.05,
                            7.0
                    );

            addRootObjectImmediate(pathTile);
        }

        /*
         * ================================================================
         * SECTION 1: ENCLOSED LIGHTING ROOM
         * ================================================================
         */

        final double roomZ = -12.0;
        final double roomWidth = 22.0;
        final double roomDepth = 18.0;
        final double roomHeight = 8.0;
        final double wallThickness = 0.30;

        final Cube roomFloor =
                new Cube(
                        1.0,
                        0.0,
                        0.0,
                        roomZ
                );

        roomFloor.setName("Lighting room floor");
        roomFloor.setTag("environment");
        roomFloor.setLayer(0);
        roomFloor.setSolid(true);

        roomFloor.getTransform().scale =
                new Vector3(
                        roomWidth,
                        0.20,
                        roomDepth
                );

        roomFloor.setMaterial(
                Material.textured(
                                Textures.checker(
                                        256,
                                        256,
                                        16,
                                        0xFFD8D8D0,
                                        0xFF555A60
                                )
                        )
                        .setTint(Color.WHITE)
                        .setWrap(Texture.Wrap.REPEAT)
                        .setAmbient(0.16)
                        .setDiffuse(0.94)
        );

        addRootObjectImmediate(roomFloor);

        final Cube roomBackWall =
                new Cube(
                        1.0,
                        0.0,
                        roomHeight * 0.5,
                        roomZ + roomDepth * 0.5
                );

        roomBackWall.setName("Lighting room back wall");
        roomBackWall.setTag("environment");
        roomBackWall.setLayer(0);
        roomBackWall.setSolid(true);
        roomBackWall.setMaterial(wallMaterial);

        roomBackWall.getTransform().scale =
                new Vector3(
                        roomWidth,
                        roomHeight,
                        wallThickness
                );

        addRootObjectImmediate(roomBackWall);

        final Cube roomLeftWall =
                new Cube(
                        1.0,
                        -roomWidth * 0.5,
                        roomHeight * 0.5,
                        roomZ
                );

        roomLeftWall.setName("Lighting room left wall");
        roomLeftWall.setTag("environment");
        roomLeftWall.setLayer(0);
        roomLeftWall.setSolid(true);

        roomLeftWall.getTransform().scale =
                new Vector3(
                        wallThickness,
                        roomHeight,
                        roomDepth
                );

        roomLeftWall.setMaterial(
                Material.solid(
                                new Color(
                                        185,
                                        55,
                                        48
                                )
                        )
                        .setAmbient(0.15)
                        .setDiffuse(0.93)
        );

        addRootObjectImmediate(roomLeftWall);

        final Cube roomRightWall =
                new Cube(
                        1.0,
                        roomWidth * 0.5,
                        roomHeight * 0.5,
                        roomZ
                );

        roomRightWall.setName("Lighting room right wall");
        roomRightWall.setTag("environment");
        roomRightWall.setLayer(0);
        roomRightWall.setSolid(true);

        roomRightWall.getTransform().scale =
                new Vector3(
                        wallThickness,
                        roomHeight,
                        roomDepth
                );

        roomRightWall.setMaterial(
                Material.solid(
                                new Color(
                                        50,
                                        75,
                                        185
                                )
                        )
                        .setAmbient(0.15)
                        .setDiffuse(0.93)
        );

        addRootObjectImmediate(roomRightWall);

        final Cube roomCeiling =
                new Cube(
                        1.0,
                        0.0,
                        roomHeight,
                        roomZ
                );

        roomCeiling.setName("Lighting room ceiling");
        roomCeiling.setTag("environment");
        roomCeiling.setLayer(0);
        roomCeiling.setSolid(true);
        roomCeiling.setMaterial(wallMaterial);

        roomCeiling.getTransform().scale =
                new Vector3(
                        roomWidth,
                        wallThickness,
                        roomDepth
                );

        addRootObjectImmediate(roomCeiling);

        /*
         * Room objects used to make the different lighting contributions clear.
         */

        final Cube roomWhiteCube =
                new Cube(
                        1.0,
                        -5.0,
                        1.5,
                        roomZ
                );

        roomWhiteCube.setName("Room diffuse white cube");
        roomWhiteCube.setTag("showcase");
        roomWhiteCube.setLayer(1);
        roomWhiteCube.setMaterial(whiteMaterial);
        roomWhiteCube.setSolid(true);

        roomWhiteCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(roomWhiteCube);

        final Cube roomTexturedCube =
                new Cube(
                        1.0,
                        0.0,
                        1.5,
                        roomZ
                );

        roomTexturedCube.setName("Room textured cube");
        roomTexturedCube.setTag("showcase");
        roomTexturedCube.setLayer(1);
        roomTexturedCube.setSolid(true);

        roomTexturedCube.setMaterial(
                Material.textured(mediumCheckerTexture)
                        .setTint(Color.WHITE)
                        .setWrap(Texture.Wrap.REPEAT)
                        .setAmbient(0.12)
                        .setDiffuse(0.95)
        );

        roomTexturedCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(roomTexturedCube);

        final Cube roomEmissiveCube =
                new Cube(
                        1.0,
                        5.0,
                        1.5,
                        roomZ
                );

        roomEmissiveCube.setName("Room emissive cube");
        roomEmissiveCube.setTag("showcase");
        roomEmissiveCube.setLayer(1);
        roomEmissiveCube.setSolid(true);

        roomEmissiveCube.setMaterial(
                Material.solid(
                                new Color(
                                        35,
                                        45,
                                        55
                                )
                        )
                        .setAmbient(0.06)
                        .setDiffuse(0.65)
                        .setEmissive(
                                new Color(
                                        80,
                                        220,
                                        255
                                ),
                                1.6,
                                12.0
                        )
        );

        roomEmissiveCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(roomEmissiveCube);

        /*
         * Point light with shadows.
         */

        final LightObject roomPointLight =
                LightObject.point(
                        new Vector3(
                                0.0,
                                6.0,
                                roomZ - 1.0
                        ),
                        new Color(
                                255,
                                225,
                                185
                        ),
                        2.0,
                        24.0,
                        true
                );

        roomPointLight.setName("Room shadow point light");
        roomPointLight.setTag("light");
        roomPointLight.setLayer(2);

        addRootObjectImmediate(roomPointLight);

        /*
         * Spot light.
         */

        final LightObject roomSpotLight =
                LightObject.spot(
                        new Vector3(
                                0.0,
                                7.0,
                                roomZ - 5.0
                        ),
                        new Vector3(
                                0.0,
                                -0.65,
                                1.0
                        ),
                        new Color(
                                170,
                                205,
                                255
                        ),
                        2.2,
                        28.0,
                        18.0,
                        30.0,
                        true
                );

        roomSpotLight.setName("Room shadow spot light");
        roomSpotLight.setTag("light");
        roomSpotLight.setLayer(2);

        addRootObjectImmediate(roomSpotLight);

        /*
         * ================================================================
         * SECTION 2: MATERIAL LIGHTING RESPONSE
         * ================================================================
         */

        final double materialZ = 10.0;

        final Material[] responseMaterials = {
                Material.solid(
                                new Color(
                                        225,
                                        225,
                                        225
                                )
                        )
                        .setAmbient(0.00)
                        .setDiffuse(1.00),

                Material.solid(
                                new Color(
                                        225,
                                        225,
                                        225
                                )
                        )
                        .setAmbient(0.20)
                        .setDiffuse(0.80),

                Material.solid(
                                new Color(
                                        225,
                                        225,
                                        225
                                )
                        )
                        .setAmbient(0.50)
                        .setDiffuse(0.50),

                Material.solid(
                                new Color(
                                        225,
                                        225,
                                        225
                                )
                        )
                        .setAmbient(0.85)
                        .setDiffuse(0.15)
        };

        final String[] materialNames = {
                "Diffuse only",
                "Low ambient",
                "Balanced ambient",
                "High ambient"
        };

        for (int index = 0; index < responseMaterials.length; index++) {

            final Cube cube =
                    new Cube(
                            1.0,
                            -9.0 + index * 6.0,
                            2.0,
                            materialZ
                    );

            cube.setName(
                    "Material response: "
                            + materialNames[index]
            );

            cube.setTag("material-test");
            cube.setLayer(1);
            cube.setSolid(true);
            cube.setMaterial(responseMaterials[index]);

            cube.getTransform().scale =
                    new Vector3(
                            3.0,
                            4.0,
                            3.0
                    );

            addRootObjectImmediate(cube);
        }

        /*
         * Tint test.
         */

        final Color[] tintColours = {
                new Color(255, 255, 255),
                new Color(255, 100, 100),
                new Color(100, 255, 130),
                new Color(100, 150, 255)
        };

        for (int index = 0; index < tintColours.length; index++) {

            final Cube cube =
                    new Cube(
                            1.0,
                            -9.0 + index * 6.0,
                            1.5,
                            materialZ + 7.0
                    );

            cube.setName(
                    "Material tint test " + index
            );

            cube.setTag("material-test");
            cube.setLayer(1);
            cube.setSolid(true);

            cube.setMaterial(
                    Material.textured(fineCheckerTexture)
                            .setTint(tintColours[index])
                            .setWrap(Texture.Wrap.REPEAT)
                            .setAmbient(0.18)
                            .setDiffuse(0.90)
            );

            cube.getTransform().scale =
                    new Vector3(
                            3.0,
                            3.0,
                            3.0
                    );

            addRootObjectImmediate(cube);
        }

        /*
         * ================================================================
         * SECTION 3: TEXTURES AND WRAPPING
         * ================================================================
         */

        final double textureZ = 22.0;

        final Texture[] textures = {
                fineCheckerTexture,
                mediumCheckerTexture,
                largeCheckerTexture,
                whiteGridTexture,
                neonGridTexture
        };

        final String[] textureNames = {
                "Fine checker",
                "Medium checker",
                "Large checker",
                "White grid",
                "Neon grid"
        };

        for (int index = 0; index < textures.length; index++) {

            final Cube cube =
                    new Cube(
                            1.0,
                            -12.0 + index * 6.0,
                            1.5,
                            textureZ
                    );

            cube.setName(
                    "Texture test: "
                            + textureNames[index]
            );

            cube.setTag("texture-test");
            cube.setLayer(1);
            cube.setSolid(true);

            cube.setMaterial(
                    Material.textured(textures[index])
                            .setTint(Color.WHITE)
                            .setWrap(Texture.Wrap.REPEAT)
                            .setAmbient(0.20)
                            .setDiffuse(0.86)
            );

            cube.getTransform().scale =
                    new Vector3(
                            3.0,
                            3.0,
                            3.0
                    );

            addRootObjectImmediate(cube);
        }

        /*
         * REPEAT versus CLAMP.
         *
         * The large scale makes the difference easier to inspect when the mesh
         * contains UV coordinates outside the standard zero-to-one range.
         */

        final Cube repeatWrapCube =
                new Cube(
                        1.0,
                        -4.0,
                        1.5,
                        textureZ + 7.0
                );

        repeatWrapCube.setName("Texture wrap REPEAT");
        repeatWrapCube.setTag("texture-wrap-test");
        repeatWrapCube.setLayer(1);
        repeatWrapCube.setSolid(true);

        repeatWrapCube.setMaterial(
                Material.textured(neonGridTexture)
                        .setTint(Color.WHITE)
                        .setWrap(Texture.Wrap.REPEAT)
                        .setAmbient(0.18)
                        .setDiffuse(0.90)
        );

        repeatWrapCube.getTransform().scale =
                new Vector3(
                        6.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(repeatWrapCube);

        final Cube clampWrapCube =
                new Cube(
                        1.0,
                        4.0,
                        1.5,
                        textureZ + 7.0
                );

        clampWrapCube.setName("Texture wrap CLAMP");
        clampWrapCube.setTag("texture-wrap-test");
        clampWrapCube.setLayer(1);
        clampWrapCube.setSolid(true);

        clampWrapCube.setMaterial(
                Material.textured(neonGridTexture)
                        .setTint(Color.WHITE)
                        .setWrap(Texture.Wrap.CLAMP)
                        .setAmbient(0.18)
                        .setDiffuse(0.90)
        );

        clampWrapCube.getTransform().scale =
                new Vector3(
                        6.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(clampWrapCube);

        /*
         * ================================================================
         * SECTION 4: EMISSIVE MATERIALS AND COLOURED LIGHTS
         * ================================================================
         */

        final double emissiveZ = 34.0;

        final Color[] emissiveColours = {
                new Color(255, 65, 55),
                new Color(75, 255, 115),
                new Color(65, 145, 255),
                new Color(220, 75, 255)
        };

        for (int index = 0; index < emissiveColours.length; index++) {

            final Color emissiveColour =
                    emissiveColours[index];

            final Cube cube =
                    new Cube(
                            1.0,
                            -9.0 + index * 6.0,
                            2.0,
                            emissiveZ
                    );

            cube.setName(
                    "Emissive material test " + index
            );

            cube.setTag("emissive-test");
            cube.setLayer(1);
            cube.setSolid(true);

            cube.setMaterial(
                    Material.solid(
                                    new Color(
                                            35,
                                            35,
                                            40
                                    )
                            )
                            .setAmbient(0.08)
                            .setDiffuse(0.55)
                            .setEmissive(
                                    emissiveColour,
                                    1.25,
                                    10.0
                            )
            );

            cube.getTransform().scale =
                    new Vector3(
                            3.0,
                            4.0,
                            3.0
                    );

            addRootObjectImmediate(cube);

            final LightObject colouredLight =
                    LightObject.point(
                            new Vector3(
                                    -9.0 + index * 6.0,
                                    5.5,
                                    emissiveZ
                            ),
                            emissiveColour,
                            1.4,
                            15.0,
                            false
                    );

            colouredLight.setName(
                    "Coloured point light " + index
            );

            colouredLight.setTag("light");
            colouredLight.setLayer(2);

            addRootObjectImmediate(colouredLight);
        }

        /*
         * Moving directional light. This exercises LightObject.update().
         */

        final LightObject rotatingDirectionalLight =
                LightObject.directional(
                        new Vector3(
                                -0.7,
                                -0.8,
                                0.35
                        ),
                        new Color(
                                255,
                                205,
                                165
                        ),
                        0.55,
                        false
                );

        rotatingDirectionalLight.setName(
                "Auto-rotating directional light"
        );

        rotatingDirectionalLight.setTag("light");
        rotatingDirectionalLight.setLayer(2);
        rotatingDirectionalLight.setAutoRotateY(
                Math.toRadians(12.0)
        );

        addRootObjectImmediate(rotatingDirectionalLight);

        /*
         * ================================================================
         * SECTION 5: TRANSFORMS, RENDER MODES AND OBJECT STATE
         * ================================================================
         */

        final double transformZ = 46.0;

        /*
         * Position test.
         */

        final Cube positionCube =
                new Cube(
                        1.0,
                        -10.0,
                        1.5,
                        transformZ
                );

        positionCube.setName("Position transform test");
        positionCube.setTag("transform-test");
        positionCube.setLayer(3);
        positionCube.setMaterial(redMaterial);
        positionCube.setSolid(true);

        positionCube.getTransform().scale =
                new Vector3(
                        2.0,
                        2.0,
                        2.0
                );

        addRootObjectImmediate(positionCube);

        /*
         * Non-uniform scale.
         */

        final Cube scaleCube =
                new Cube(
                        1.0,
                        -4.0,
                        1.5,
                        transformZ
                );

        scaleCube.setName("Non-uniform scale test");
        scaleCube.setTag("transform-test");
        scaleCube.setLayer(3);
        scaleCube.setMaterial(greenMaterial);
        scaleCube.setSolid(true);

        scaleCube.getTransform().scale =
                new Vector3(
                        5.0,
                        1.5,
                        2.0
                );

        addRootObjectImmediate(scaleCube);

        /*
         * Three-axis rotation.
         */

        final Cube rotationCube =
                new Cube(
                        1.0,
                        3.0,
                        2.0,
                        transformZ
                );

        rotationCube.setName("Three-axis rotation test");
        rotationCube.setTag("transform-test");
        rotationCube.setLayer(3);
        rotationCube.setMaterial(blueMaterial);
        rotationCube.setSolid(true);

        rotationCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        rotationCube.getTransform().rotation =
                new Vector3(
                        Math.toRadians(25.0),
                        Math.toRadians(40.0),
                        Math.toRadians(15.0)
                );

        addRootObjectImmediate(rotationCube);

        /*
         * Wireframe rendering.
         */

        final Cube wireframeCube =
                new Cube(
                        1.0,
                        10.0,
                        2.0,
                        transformZ
                );

        wireframeCube.setName("Wireframe rendering test");
        wireframeCube.setTag("render-mode-test");
        wireframeCube.setLayer(3);
        wireframeCube.setColor(
                new Color(
                        255,
                        235,
                        70
                )
        );
        wireframeCube.setSolid(false);
        wireframeCube.setWireframe(true);

        wireframeCube.getTransform().scale =
                new Vector3(
                        4.0,
                        4.0,
                        4.0
                );

        wireframeCube.getTransform().rotation =
                new Vector3(
                        Math.toRadians(20.0),
                        Math.toRadians(35.0),
                        0.0
                );

        addRootObjectImmediate(wireframeCube);

        /*
         * Visible but non-solid object.
         *
         * This should render but should not appear in the collider index.
         */

        final Cube nonSolidCube =
                new Cube(
                        1.0,
                        -7.0,
                        1.5,
                        transformZ + 7.0
                );

        nonSolidCube.setName("Visible non-solid object");
        nonSolidCube.setTag("state-test");
        nonSolidCube.setLayer(4);
        nonSolidCube.setMaterial(
                Material.solid(
                                new Color(
                                        255,
                                        165,
                                        40
                                )
                        )
                        .setAmbient(0.20)
                        .setDiffuse(0.85)
        );

        nonSolidCube.setSolid(false);
        nonSolidCube.setVisible(true);

        nonSolidCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(nonSolidCube);

        /*
         * Solid object that ignores player collision response.
         */

        final Cube ignoredCollisionCube =
                new Cube(
                        1.0,
                        0.0,
                        1.5,
                        transformZ + 7.0
                );

        ignoredCollisionCube.setName(
                "Solid object ignoring player collisions"
        );

        ignoredCollisionCube.setTag("state-test");
        ignoredCollisionCube.setLayer(4);
        ignoredCollisionCube.setMaterial(
                Material.solid(
                                new Color(
                                        170,
                                        80,
                                        255
                                )
                        )
                        .setAmbient(0.18)
                        .setDiffuse(0.90)
        );

        ignoredCollisionCube.setSolid(true);
        ignoredCollisionCube.setIgnorePlayerCollisions(true);

        ignoredCollisionCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(ignoredCollisionCube);

        /*
         * Hidden object.
         *
         * It is deliberately placed beside the state-test objects. It should be
         * present in the scene graph but should not render.
         */

        final Cube hiddenCube =
                new Cube(
                        1.0,
                        7.0,
                        1.5,
                        transformZ + 7.0
                );

        hiddenCube.setName("Hidden visibility test object");
        hiddenCube.setTag("state-test");
        hiddenCube.setLayer(4);
        hiddenCube.setMaterial(redMaterial);
        hiddenCube.setSolid(false);
        hiddenCube.setVisible(false);

        hiddenCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(hiddenCube);

        /*
         * Inactive object.
         *
         * It remains in the hierarchy but should be skipped by update systems.
         */

        final Cube inactiveCube =
                new Cube(
                        1.0,
                        12.0,
                        1.5,
                        transformZ + 7.0
                );

        inactiveCube.setName("Inactive update test object");
        inactiveCube.setTag("state-test");
        inactiveCube.setLayer(4);
        inactiveCube.setMaterial(blueMaterial);
        inactiveCube.setSolid(false);
        inactiveCube.setActive(false);

        inactiveCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        addRootObjectImmediate(inactiveCube);

        /*
         * ================================================================
         * SECTION 6: ANIMATION
         * ================================================================
         */

        final double animationZ = 60.0;

        /*
         * Relative looping rotation.
         */

        final Cube rotatingCube =
                new Cube(
                        1.0,
                        -12.0,
                        2.0,
                        animationZ
                );

        rotatingCube.setName("Relative looping rotation");
        rotatingCube.setTag("animation-test");
        rotatingCube.setLayer(5);
        rotatingCube.setSolid(true);

        rotatingCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        rotatingCube.setMaterial(
                Material.textured(neonGridTexture)
                        .setTint(Color.WHITE)
                        .setWrap(Texture.Wrap.REPEAT)
                        .setAmbient(0.18)
                        .setDiffuse(0.92)
        );

        rotatingCube.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(1.0)
                .play(
                        AnimationClip.builder(6.0)
                                .loop(true)
                                .rot(
                                        0.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .rot(
                                        3.0,
                                        Math.PI,
                                        Math.PI,
                                        0.0
                                )
                                .rot(
                                        6.0,
                                        Math.PI * 2.0,
                                        Math.PI * 2.0,
                                        Math.PI * 2.0
                                )
                                .build()
                );

        addRootObjectImmediate(rotatingCube);

        /*
         * Relative position animation.
         */

        final Cube movingCube =
                new Cube(
                        1.0,
                        -4.0,
                        1.5,
                        animationZ
                );

        movingCube.setName("Relative position animation");
        movingCube.setTag("animation-test");
        movingCube.setLayer(5);
        movingCube.setSolid(true);

        movingCube.getTransform().scale =
                new Vector3(
                        2.0,
                        2.0,
                        2.0
                );

        movingCube.setMaterial(
                Material.solid(
                                new Color(
                                        255,
                                        125,
                                        45
                                )
                        )
                        .setAmbient(0.18)
                        .setDiffuse(0.92)
        );

        movingCube.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(1.0)
                .play(
                        AnimationClip.builder(4.0)
                                .loop(true)
                                .pos(
                                        0.0,
                                        -3.0,
                                        0.0,
                                        0.0
                                )
                                .pos(
                                        1.0,
                                        0.0,
                                        3.0,
                                        0.0
                                )
                                .pos(
                                        2.0,
                                        3.0,
                                        0.0,
                                        0.0
                                )
                                .pos(
                                        3.0,
                                        0.0,
                                        1.2,
                                        0.0
                                )
                                .pos(
                                        4.0,
                                        -3.0,
                                        0.0,
                                        0.0
                                )
                                .build()
                );

        addRootObjectImmediate(movingCube);

        /*
         * Relative scale animation.
         */

        final Cube pulsingCube =
                new Cube(
                        1.0,
                        4.0,
                        2.0,
                        animationZ
                );

        pulsingCube.setName("Relative scale animation");
        pulsingCube.setTag("animation-test");
        pulsingCube.setLayer(5);
        pulsingCube.setSolid(true);

        pulsingCube.getTransform().scale =
                new Vector3(
                        2.0,
                        2.0,
                        2.0
                );

        pulsingCube.setMaterial(
                Material.solid(
                                new Color(
                                        115,
                                        255,
                                        135
                                )
                        )
                        .setAmbient(0.16)
                        .setDiffuse(0.90)
                        .setEmissive(
                                new Color(
                                        70,
                                        255,
                                        100
                                ),
                                0.35,
                                7.0
                        )
        );

        pulsingCube.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(1.0)
                .play(
                        AnimationClip.builder(2.0)
                                .loop(true)
                                .scale(
                                        0.0,
                                        0.65,
                                        0.65,
                                        0.65
                                )
                                .scale(
                                        1.0,
                                        1.45,
                                        1.45,
                                        1.45
                                )
                                .scale(
                                        2.0,
                                        0.65,
                                        0.65,
                                        0.65
                                )
                                .build()
                );

        addRootObjectImmediate(pulsingCube);

        /*
         * Absolute, non-looping animation.
         *
         * This moves to exact world-local transform values rather than applying
         * offsets to the starting transform. At the end, Animator should stop
         * automatically and leave the object at its final keyframe.
         */

        final Cube absoluteNonLoopingCube =
                new Cube(
                        1.0,
                        10.0,
                        1.0,
                        animationZ
                );

        absoluteNonLoopingCube.setName(
                "Absolute non-looping animation"
        );

        absoluteNonLoopingCube.setTag("animation-test");
        absoluteNonLoopingCube.setLayer(5);
        absoluteNonLoopingCube.setSolid(true);

        absoluteNonLoopingCube.setMaterial(
                Material.solid(
                                new Color(
                                        255,
                                        210,
                                        70
                                )
                        )
                        .setAmbient(0.20)
                        .setDiffuse(0.88)
        );

        absoluteNonLoopingCube.animate()
                .setMode(Animator.Mode.ABSOLUTE)
                .setSpeed(1.0)
                .play(
                        AnimationClip.builder(5.0)
                                .loop(false)
                                .pos(
                                        0.0,
                                        10.0,
                                        1.0,
                                        animationZ
                                )
                                .rot(
                                        0.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .scale(
                                        0.0,
                                        1.0,
                                        1.0,
                                        1.0
                                )
                                .pos(
                                        2.5,
                                        10.0,
                                        5.0,
                                        animationZ
                                )
                                .rot(
                                        2.5,
                                        Math.PI,
                                        Math.PI,
                                        0.0
                                )
                                .scale(
                                        2.5,
                                        2.5,
                                        2.5,
                                        2.5
                                )
                                .pos(
                                        5.0,
                                        10.0,
                                        1.0,
                                        animationZ + 4.0
                                )
                                .rot(
                                        5.0,
                                        Math.PI * 2.0,
                                        Math.PI * 2.0,
                                        Math.PI * 2.0
                                )
                                .scale(
                                        5.0,
                                        1.5,
                                        1.5,
                                        1.5
                                )
                                .build()
                );

        addRootObjectImmediate(absoluteNonLoopingCube);

        /*
         * Single-channel animation.
         *
         * Only POS_Y is keyed. X, Z, rotation, and scale should remain at their
         * base values. This tests AnimationClip.Channel and NaN channel fallback.
         */

        final Cube singleChannelCube =
                new Cube(
                        1.0,
                        -8.0,
                        1.5,
                        animationZ + 8.0
                );

        singleChannelCube.setName("Single-channel Y animation");
        singleChannelCube.setTag("animation-test");
        singleChannelCube.setLayer(5);
        singleChannelCube.setSolid(true);

        singleChannelCube.getTransform().scale =
                new Vector3(
                        2.5,
                        2.5,
                        2.5
                );

        singleChannelCube.getTransform().rotation =
                new Vector3(
                        0.0,
                        Math.toRadians(30.0),
                        0.0
                );

        singleChannelCube.setMaterial(
                Material.solid(
                                new Color(
                                        70,
                                        210,
                                        255
                                )
                        )
                        .setAmbient(0.16)
                        .setDiffuse(0.92)
        );

        singleChannelCube.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(1.0)
                .play(
                        AnimationClip.builder(3.0)
                                .loop(true)
                                .key(
                                        AnimationClip.Channel.POS_Y,
                                        0.0,
                                        0.0
                                )
                                .key(
                                        AnimationClip.Channel.POS_Y,
                                        1.5,
                                        4.0
                                )
                                .key(
                                        AnimationClip.Channel.POS_Y,
                                        3.0,
                                        0.0
                                )
                                .build()
                );

        addRootObjectImmediate(singleChannelCube);

        /*
         * Reverse playback.
         *
         * A negative speed exercises looping with negative animation time.
         */

        final Cube reverseAnimationCube =
                new Cube(
                        1.0,
                        0.0,
                        2.0,
                        animationZ + 8.0
                );

        reverseAnimationCube.setName("Reverse-speed animation");
        reverseAnimationCube.setTag("animation-test");
        reverseAnimationCube.setLayer(5);
        reverseAnimationCube.setSolid(true);

        reverseAnimationCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        reverseAnimationCube.setMaterial(
                Material.textured(largeCheckerTexture)
                        .setTint(Color.WHITE)
                        .setWrap(Texture.Wrap.REPEAT)
                        .setAmbient(0.18)
                        .setDiffuse(0.90)
        );

        reverseAnimationCube.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(-0.75)
                .play(
                        AnimationClip.builder(5.0)
                                .loop(true)
                                .rot(
                                        0.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .rot(
                                        5.0,
                                        0.0,
                                        Math.PI * 2.0,
                                        0.0
                                )
                                .build()
                );

        addRootObjectImmediate(reverseAnimationCube);

        /*
         * Faster playback.
         */

        final Cube fastAnimationCube =
                new Cube(
                        1.0,
                        8.0,
                        2.0,
                        animationZ + 8.0
                );

        fastAnimationCube.setName("Double-speed animation");
        fastAnimationCube.setTag("animation-test");
        fastAnimationCube.setLayer(5);
        fastAnimationCube.setSolid(true);

        fastAnimationCube.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        fastAnimationCube.setMaterial(
                Material.solid(
                                new Color(
                                        255,
                                        95,
                                        175
                                )
                        )
                        .setAmbient(0.18)
                        .setDiffuse(0.90)
        );

        fastAnimationCube.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(2.0)
                .play(
                        AnimationClip.builder(4.0)
                                .loop(true)
                                .rot(
                                        0.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .rot(
                                        2.0,
                                        Math.PI,
                                        Math.PI,
                                        Math.PI
                                )
                                .rot(
                                        4.0,
                                        Math.PI * 2.0,
                                        Math.PI * 2.0,
                                        Math.PI * 2.0
                                )
                                .build()
                );

        addRootObjectImmediate(fastAnimationCube);

        /*
         * ================================================================
         * SECTION 7: HIERARCHY
         * ================================================================
         */

        final double hierarchyZ = 76.0;

        final Cube hierarchyParent =
                new Cube(
                        1.0,
                        0.0,
                        2.0,
                        hierarchyZ
                );

        hierarchyParent.setName("Hierarchy parent");
        hierarchyParent.setTag("hierarchy-parent");
        hierarchyParent.setLayer(6);
        hierarchyParent.setSolid(true);

        hierarchyParent.getTransform().scale =
                new Vector3(
                        3.0,
                        3.0,
                        3.0
                );

        hierarchyParent.setMaterial(
                Material.solid(
                                new Color(
                                        240,
                                        200,
                                        65
                                )
                        )
                        .setAmbient(0.20)
                        .setDiffuse(0.85)
        );

        final Cube hierarchyChild =
                new Cube(
                        1.0,
                        5.0,
                        0.0,
                        0.0
                );

        hierarchyChild.setName("Hierarchy orbiting child");
        hierarchyChild.setTag("hierarchy-child");
        hierarchyChild.setLayer(6);
        hierarchyChild.setSolid(true);

        hierarchyChild.getTransform().scale =
                new Vector3(
                        0.65,
                        0.65,
                        0.65
                );

        hierarchyChild.setMaterial(
                Material.solid(
                                new Color(
                                        85,
                                        180,
                                        255
                                )
                        )
                        .setAmbient(0.16)
                        .setDiffuse(0.92)
                        .setEmissive(
                                new Color(
                                        60,
                                        145,
                                        255
                                ),
                                0.25,
                                6.0
                        )
        );

        final Cube hierarchyGrandchild =
                new Cube(
                        1.0,
                        2.5,
                        0.0,
                        0.0
                );

        hierarchyGrandchild.setName("Hierarchy grandchild");
        hierarchyGrandchild.setTag("hierarchy-grandchild");
        hierarchyGrandchild.setLayer(6);
        hierarchyGrandchild.setSolid(true);

        hierarchyGrandchild.getTransform().scale =
                new Vector3(
                        0.50,
                        0.50,
                        0.50
                );

        hierarchyGrandchild.setMaterial(
                Material.solid(
                                new Color(
                                        255,
                                        90,
                                        160
                                )
                        )
                        .setAmbient(0.18)
                        .setDiffuse(0.90)
        );

        hierarchyChild.addChild(hierarchyGrandchild);
        hierarchyParent.addChild(hierarchyChild);

        hierarchyParent.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(1.0)
                .play(
                        AnimationClip.builder(8.0)
                                .loop(true)
                                .rot(
                                        0.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .rot(
                                        4.0,
                                        0.0,
                                        Math.PI,
                                        0.0
                                )
                                .rot(
                                        8.0,
                                        0.0,
                                        Math.PI * 2.0,
                                        0.0
                                )
                                .build()
                );

        hierarchyChild.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(2.0)
                .play(
                        AnimationClip.builder(4.0)
                                .loop(true)
                                .rot(
                                        0.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .rot(
                                        4.0,
                                        Math.PI * 2.0,
                                        Math.PI * 2.0,
                                        0.0
                                )
                                .build()
                );

        hierarchyGrandchild.animate()
                .setMode(Animator.Mode.RELATIVE)
                .setSpeed(1.0)
                .play(
                        AnimationClip.builder(2.0)
                                .loop(true)
                                .scale(
                                        0.0,
                                        0.70,
                                        0.70,
                                        0.70
                                )
                                .scale(
                                        1.0,
                                        1.35,
                                        1.35,
                                        1.35
                                )
                                .scale(
                                        2.0,
                                        0.70,
                                        0.70,
                                        0.70
                                )
                                .build()
                );

        addRootObjectImmediate(hierarchyParent);

        /*
         * A second hierarchy tests independent parent rotation and child
         * translation animation.
         */

        final Cube secondParent =
                new Cube(
                        1.0,
                        -12.0,
                        1.5,
                        hierarchyZ
                );

        secondParent.setName("Second hierarchy parent");
        secondParent.setTag("hierarchy-parent");
        secondParent.setLayer(6);
        secondParent.setSolid(true);
        secondParent.setMaterial(redMaterial);

        secondParent.getTransform().scale =
                new Vector3(
                        2.0,
                        2.0,
                        2.0
                );

        final Cube secondChild =
                new Cube(
                        1.0,
                        4.0,
                        0.0,
                        0.0
                );

        secondChild.setName("Animated hierarchy child");
        secondChild.setTag("hierarchy-child");
        secondChild.setLayer(6);
        secondChild.setSolid(true);
        secondChild.setMaterial(greenMaterial);

        secondChild.getTransform().scale =
                new Vector3(
                        0.75,
                        0.75,
                        0.75
                );

        secondParent.addChild(secondChild);

        secondParent.animate()
                .setMode(Animator.Mode.RELATIVE)
                .play(
                        AnimationClip.builder(7.0)
                                .loop(true)
                                .rot(
                                        0.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .rot(
                                        7.0,
                                        0.0,
                                        -Math.PI * 2.0,
                                        0.0
                                )
                                .build()
                );

        secondChild.animate()
                .setMode(Animator.Mode.RELATIVE)
                .play(
                        AnimationClip.builder(3.0)
                                .loop(true)
                                .pos(
                                        0.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .pos(
                                        1.5,
                                        0.0,
                                        2.0,
                                        0.0
                                )
                                .pos(
                                        3.0,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                                .build()
                );

        addRootObjectImmediate(secondParent);

        /*
         * ================================================================
         * SECTION 8: IMPORTED MESH GALLERY
         * ================================================================
         */

        final double importedGalleryZ = 92.0;

        if (meshIds.isEmpty()) {

            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "No imported meshes were found under: "
                            + context.assetManager
                            .getModelsRoot()
                            .toAbsolutePath()
            );

            final Cube missingMeshMarker =
                    new Cube(
                            1.0,
                            0.0,
                            2.0,
                            importedGalleryZ
                    );

            missingMeshMarker.setName(
                    "No imported meshes available"
            );

            missingMeshMarker.setTag("asset-test");
            missingMeshMarker.setLayer(7);
            missingMeshMarker.setSolid(false);
            missingMeshMarker.setWireframe(true);
            missingMeshMarker.setColor(Color.RED);

            missingMeshMarker.getTransform().scale =
                    new Vector3(
                            5.0,
                            5.0,
                            5.0
                    );

            addRootObjectImmediate(missingMeshMarker);

        } else {

            final double spacing = 7.0;

            final int columns =
                    Math.max(
                            1,
                            (int) Math.ceil(
                                    Math.sqrt(meshIds.size())
                            )
                    );

            final double firstX =
                    -(
                            (
                                    Math.min(
                                            columns,
                                            meshIds.size()
                                    ) - 1
                            ) * spacing
                    ) * 0.5;

            for (int index = 0; index < meshIds.size(); index++) {

                final String meshId =
                        meshIds.get(index);

                final MeshData mesh =
                        context.assetManager.getMeshOrNull(
                                meshId
                        );

                if (mesh == null) {

                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "Mesh ID was listed but could not be resolved: "
                                    + meshId
                    );

                    continue;
                }

                final int column =
                        index % columns;

                final int row =
                        index / columns;

                final MeshObject importedObject =
                        new MeshObject(mesh);

                importedObject.setName(
                        "Imported mesh: " + mesh.getId()
                );

                importedObject.setTag("imported-mesh");
                importedObject.setLayer(7);
                importedObject.setSolid(true);
                importedObject.setWireframe(false);

                importedObject.setMaterial(
                        Material.textured(
                                        index % 2 == 0
                                                ? mediumCheckerTexture
                                                : whiteGridTexture
                                )
                                .setTint(
                                        index % 3 == 0
                                                ? new Color(
                                                255,
                                                225,
                                                180
                                        )
                                                : Color.WHITE
                                )
                                .setWrap(Texture.Wrap.REPEAT)
                                .setAmbient(0.18)
                                .setDiffuse(0.90)
                );

                importedObject.getTransform().position =
                        new Vector3(
                                firstX + column * spacing,
                                1.5,
                                importedGalleryZ
                                        + row * spacing
                        );

                importedObject.getTransform().scale =
                        new Vector3(
                                2.5,
                                2.5,
                                2.5
                        );

                importedObject.getTransform().rotation =
                        new Vector3(
                                0.0,
                                index * 0.35,
                                0.0
                        );

                /*
                 * Animate alternating imported meshes to confirm that MeshObject
                 * participates in the same transform and animation systems.
                 */

                if ((index & 1) == 0) {

                    importedObject.animate()
                            .setMode(Animator.Mode.RELATIVE)
                            .setSpeed(
                                    0.60 + index * 0.03
                            )
                            .play(
                                    AnimationClip.builder(6.0)
                                            .loop(true)
                                            .rot(
                                                    0.0,
                                                    0.0,
                                                    0.0,
                                                    0.0
                                            )
                                            .rot(
                                                    6.0,
                                                    0.0,
                                                    Math.PI * 2.0,
                                                    0.0
                                            )
                                            .build()
                            );
                }

                addRootObjectImmediate(importedObject);
            }

            /*
             * Test filename alias lookup using the first mesh ID.
             *
             * The AssetManager only publishes an alias when the filename is
             * unambiguous. Therefore, alias resolution is allowed to return null.
             */

            final String firstMeshId =
                    meshIds.get(0);

            final int slashIndex =
                    firstMeshId.lastIndexOf('/');

            final String filePart =
                    slashIndex >= 0
                            ? firstMeshId.substring(
                            slashIndex + 1
                    )
                            : firstMeshId;

            final int dotIndex =
                    filePart.lastIndexOf('.');

            final String possibleAlias =
                    dotIndex > 0
                            ? filePart.substring(
                            0,
                            dotIndex
                    )
                            : filePart;

            final MeshData aliasResolvedMesh =
                    context.assetManager.getMeshOrNull(
                            possibleAlias
                    );

            LOGGER.log(
                    System.Logger.Level.INFO,
                    aliasResolvedMesh != null
                            ? "Asset alias test passed: '"
                            + possibleAlias
                            + "' resolved to '"
                            + aliasResolvedMesh.getId()
                            + "'"
                            : "Asset alias test skipped: alias '"
                            + possibleAlias
                            + "' is unavailable or ambiguous"
            );
        }

        /*
         * ================================================================
         * GLOBAL LIGHTING
         * ================================================================
         */

        final LightObject sunLight =
                LightObject.directional(
                        new Vector3(
                                -0.65,
                                -1.0,
                                0.35
                        ),
                        new Color(
                                255,
                                238,
                                210
                        ),
                        0.72,
                        true
                );

        sunLight.setName("Warm global sun light");
        sunLight.setTag("light");
        sunLight.setLayer(2);

        addRootObjectImmediate(sunLight);

        final LightObject skyFill =
                LightObject.directional(
                        new Vector3(
                                0.55,
                                -0.35,
                                -0.75
                        ),
                        new Color(
                                145,
                                175,
                                255
                        ),
                        0.22,
                        false
                );

        skyFill.setName("Cool global fill light");
        skyFill.setTag("light");
        skyFill.setLayer(2);

        addRootObjectImmediate(skyFill);

        /*
         * ================================================================
         * RUNTIME SANITY CHECKS
         * ================================================================
         */

        if (hierarchyChild.getParent() != hierarchyParent) {
            throw new IllegalStateException(
                    "Hierarchy test failed: child parent was not assigned"
            );
        }

        if (hierarchyGrandchild.getParent() != hierarchyChild) {
            throw new IllegalStateException(
                    "Hierarchy test failed: grandchild parent was not assigned"
            );
        }

        if (!hierarchyParent.getChildren().contains(hierarchyChild)) {
            throw new IllegalStateException(
                    "Hierarchy test failed: parent does not contain child"
            );
        }

        if (!hierarchyChild
                .getChildren()
                .contains(hierarchyGrandchild)) {

            throw new IllegalStateException(
                    "Hierarchy test failed: child does not contain grandchild"
            );
        }

        if (rotatingCube.getAnimator() == null
                || !rotatingCube
                .getAnimator()
                .isPlaying()) {

            throw new IllegalStateException(
                    "Animation test failed: rotating cube animator is not playing"
            );
        }

        if (absoluteNonLoopingCube.getAnimator() == null) {
            throw new IllegalStateException(
                    "Animation test failed: absolute animator was not created"
            );
        }

        if (!"animation-test".equals(
                rotatingCube.getTag()
        )) {
            throw new IllegalStateException(
                    "Metadata test failed: object tag was not retained"
            );
        }

        if (rotatingCube.getLayer() != 5) {
            throw new IllegalStateException(
                    "Metadata test failed: object layer was not retained"
            );
        }

        if (!ignoredCollisionCube
                .isIgnorePlayerCollisions()) {

            throw new IllegalStateException(
                    "Collision flag test failed"
            );
        }

        if (hiddenCube.isVisible()) {
            throw new IllegalStateException(
                    "Visibility test failed: hidden object is visible"
            );
        }

        if (inactiveCube.isActive()) {
            throw new IllegalStateException(
                    "Active-state test failed: inactive object is active"
            );
        }

        if (nonSolidCube.isSolid()) {
            throw new IllegalStateException(
                    "Solid-state test failed: non-solid object is solid"
            );
        }

        if (!wireframeCube.isWireframe()) {
            throw new IllegalStateException(
                    "Wireframe-state test failed"
            );
        }

        /*
         * ================================================================
         * INVENTORY INITIALISATION
         * ================================================================
         */

        context.inventorySystem.spawnItemsOnInit();
        context.inventorySystem.seedStartingInventory();

        LOGGER.log(
                System.Logger.Level.INFO,
                "Complete engine showcase initialised with "
                        + rootObjects.size()
                        + " root objects, "
                        + meshIds.size()
                        + " imported mesh assets and hierarchy version "
                        + GameObject.getHierarchyVersion()
        );
    }

    public void drainRootOperations() {
        mutationQueue.drain(rootObjects);
    }

    public void runTwoPhaseUpdate(double delta) {
        final FrameGraph graph = sceneGraphUpdater.buildFrameGraph(rootObjects);

        if (graph.count <= 0) {
            return;
        }

        final int objectCount = graph.count;
        final int workers = updateScheduler.workerCount(objectCount);

        ensureUpdateContexts(workers);
        transformPublisher.ensureWorldScratch(objectCount);

        final int writeFrame = 1 - GameObject.getPublishedFrameIndex();

        for (int i = 0; i < workers; i++) {
            updateContexts[i].colliders.reset();
            updateContexts[i].renderables.reset();
            updateContexts[i].writeFrame = writeFrame;
        }

        updateScheduler.updateGameplayObjects(graph.objects, objectCount, delta);

        if (graph.rebuilt) {
            for (int i = 0; i < objectCount; i++) {
                final GameObject object = graph.objects[i];

                if (object != null) {
                    derivedCache.cacheFor(object);
                }
            }
        }

        updateScheduler.computeDerivedDataByDepth(graph, workers, updateContexts);
        spatialIndexes.flushAndPublish(workers, updateContexts);
    }

    private void ensureUpdateContexts(int workers) {
        if (updateContexts.length < workers) {
            final UpdateContext[] expanded = new UpdateContext[workers];

            System.arraycopy(updateContexts, 0, expanded, 0, updateContexts.length);

            for (int i = updateContexts.length; i < workers; i++) {
                expanded[i] = new UpdateContext();
            }

            updateContexts = expanded;
        }
    }
}