package objects.dynamic;

import engine.render.Material;
import objects.GameObject;
import util.Vector3;

import java.awt.Color;

final class BodyBuilder {

    static BodyParts build(
            Body body,
            BodyDimensions dimensions
    ) {
        final double w = dimensions.w;
        final double pelvisH = dimensions.pelvisH;
        final double torsoH = dimensions.torsoH;
        final double neckH = dimensions.neckH;
        final double headR = dimensions.headR;

        final double thighL = dimensions.thighL;
        final double shinL = dimensions.shinL;
        final double footH = dimensions.footH;

        final double uArmL = dimensions.uArmL;
        final double fArmL = dimensions.fArmL;

        final double handR = dimensions.handR;
        final double legR = dimensions.legR;
        final double armR = dimensions.armR;
        final double torsoR = dimensions.torsoR;
        final double neckR = dimensions.neckR;

        final double legTotal =
                dimensions.legTotal;

        final Material skin =
                Material.solid(
                                new Color(255, 196, 160)
                        )
                        .setAmbient(0.22)
                        .setDiffuse(0.90);

        final Material shirt =
                Material.solid(
                                new Color(70, 170, 255)
                        )
                        .setAmbient(0.22)
                        .setDiffuse(0.90);

        final Material pants =
                Material.solid(
                                new Color(50, 70, 95)
                        )
                        .setAmbient(0.22)
                        .setDiffuse(0.90);

        final Material shoes =
                Material.solid(
                                new Color(35, 35, 35)
                        )
                        .setAmbient(0.20)
                        .setDiffuse(0.95);

        final BoxPart pelvis = new BoxPart(
                w * 0.55,
                pelvisH,
                w * 0.30,
                BoxPart.Anchor.BOTTOM
        );

        pelvis.setMaterial(pants);
        pelvis.getTransform().position =
                new Vector3(0.0, legTotal, 0.0);

        final CapsulePart torso = new CapsulePart(
                torsoR,
                torsoH,
                12,
                4,
                2,
                CapsulePart.Anchor.BOTTOM
        );

        torso.setMaterial(shirt);
        torso.getTransform().position =
                new Vector3(
                        0.0,
                        pelvisH * 0.98,
                        0.0
                );

        final CapsulePart neck = new CapsulePart(
                neckR,
                neckH,
                12,
                3,
                1,
                CapsulePart.Anchor.BOTTOM
        );

        neck.setMaterial(skin);
        neck.getTransform().position =
                new Vector3(
                        0.0,
                        torsoH * 0.985,
                        0.0
                );

        final SpherePart head = new SpherePart(
                headR,
                16,
                10,
                SpherePart.Anchor.BOTTOM
        );

        head.setMaterial(skin);
        head.getTransform().position =
                new Vector3(
                        0.0,
                        neckH * 0.98,
                        0.0
                );

        final double shoulderY =
                torsoH * 0.82;

        final double shoulderX =
                torsoR + armR * 1.75;

        final double shoulderZ =
                torsoR * 0.16;

        final CapsulePart lUpperArm =
                new CapsulePart(
                        armR,
                        uArmL,
                        12,
                        3,
                        2,
                        CapsulePart.Anchor.TOP
                );

        lUpperArm.setMaterial(skin);
        lUpperArm.getTransform().position =
                new Vector3(
                        -shoulderX,
                        shoulderY,
                        shoulderZ
                );

        final CapsulePart lForeArm =
                new CapsulePart(
                        armR * 0.92,
                        fArmL,
                        12,
                        3,
                        2,
                        CapsulePart.Anchor.TOP
                );

        lForeArm.setMaterial(skin);
        lForeArm.getTransform().position =
                new Vector3(
                        0.0,
                        -uArmL,
                        0.0
                );

        final SpherePart lHand =
                new SpherePart(
                        handR,
                        14,
                        8,
                        SpherePart.Anchor.TOP
                );

        lHand.setMaterial(skin);
        lHand.getTransform().position =
                new Vector3(
                        0.0,
                        -fArmL,
                        0.0
                );

        final CapsulePart rUpperArm =
                new CapsulePart(
                        armR,
                        uArmL,
                        12,
                        3,
                        2,
                        CapsulePart.Anchor.TOP
                );

        rUpperArm.setMaterial(skin);
        rUpperArm.getTransform().position =
                new Vector3(
                        shoulderX,
                        shoulderY,
                        shoulderZ
                );

        final CapsulePart rForeArm =
                new CapsulePart(
                        armR * 0.92,
                        fArmL,
                        12,
                        3,
                        2,
                        CapsulePart.Anchor.TOP
                );

        rForeArm.setMaterial(skin);
        rForeArm.getTransform().position =
                new Vector3(
                        0.0,
                        -uArmL,
                        0.0
                );

        final SpherePart rHand =
                new SpherePart(
                        handR,
                        14,
                        8,
                        SpherePart.Anchor.TOP
                );

        rHand.setMaterial(skin);
        rHand.getTransform().position =
                new Vector3(
                        0.0,
                        -fArmL,
                        0.0
                );

        lUpperArm.getTransform().rotation.z = -0.40;
        rUpperArm.getTransform().rotation.z = 0.40;

        lUpperArm.getTransform().rotation.y = 0.10;
        rUpperArm.getTransform().rotation.y = -0.10;

        lForeArm.getTransform().rotation.x = 0.12;
        rForeArm.getTransform().rotation.x = 0.12;

        lHand.getTransform().rotation.x = 0.05;
        rHand.getTransform().rotation.x = 0.05;

        final double hipY =
                pelvisH * 0.10;

        final double hipX =
                w * 0.18;

        final CapsulePart lThigh =
                new CapsulePart(
                        legR,
                        thighL,
                        12,
                        3,
                        2,
                        CapsulePart.Anchor.TOP
                );

        lThigh.setMaterial(pants);
        lThigh.getTransform().position =
                new Vector3(-hipX, hipY, 0.0);

        final CapsulePart lShin =
                new CapsulePart(
                        legR * 0.92,
                        shinL,
                        12,
                        3,
                        2,
                        CapsulePart.Anchor.TOP
                );

        lShin.setMaterial(pants);
        lShin.getTransform().position =
                new Vector3(0.0, -thighL, 0.0);

        final BoxPart lFoot = new BoxPart(
                w * 0.22,
                footH,
                w * 0.38,
                BoxPart.Anchor.TOP
        );

        lFoot.setMaterial(shoes);
        lFoot.getTransform().position =
                new Vector3(
                        0.0,
                        -shinL,
                        w * 0.10
                );

        final CapsulePart rThigh =
                new CapsulePart(
                        legR,
                        thighL,
                        12,
                        3,
                        2,
                        CapsulePart.Anchor.TOP
                );

        rThigh.setMaterial(pants);
        rThigh.getTransform().position =
                new Vector3(hipX, hipY, 0.0);

        final CapsulePart rShin =
                new CapsulePart(
                        legR * 0.92,
                        shinL,
                        12,
                        3,
                        2,
                        CapsulePart.Anchor.TOP
                );

        rShin.setMaterial(pants);
        rShin.getTransform().position =
                new Vector3(0.0, -thighL, 0.0);

        final BoxPart rFoot = new BoxPart(
                w * 0.22,
                footH,
                w * 0.38,
                BoxPart.Anchor.TOP
        );

        rFoot.setMaterial(shoes);
        rFoot.getTransform().position =
                new Vector3(
                        0.0,
                        -shinL,
                        w * 0.10
                );

        body.addChild(pelvis);

        pelvis.addChild(torso);

        torso.addChild(neck);
        neck.addChild(head);

        torso.addChild(lUpperArm);
        lUpperArm.addChild(lForeArm);
        lForeArm.addChild(lHand);

        torso.addChild(rUpperArm);
        rUpperArm.addChild(rForeArm);
        rForeArm.addChild(rHand);

        pelvis.addChild(lThigh);
        lThigh.addChild(lShin);
        lShin.addChild(lFoot);

        pelvis.addChild(rThigh);
        rThigh.addChild(rShin);
        rShin.addChild(rFoot);

        final GameObject rightHandSocket =
                new HandSocketNode();

        rightHandSocket.setName("hand_socket");
        rightHandSocket.setVisible(false);
        rightHandSocket.setActive(true);
        rightHandSocket.setSolid(false);
        rightHandSocket.setIgnorePlayerCollisions(
                true
        );

        rightHandSocket.getTransform().position =
                new Vector3(
                        0.0,
                        -handR * 0.95,
                        handR * 0.65
                );

        rHand.addChild(rightHandSocket);

        return new BodyParts(
                pelvis,
                torso,
                neck,
                head,
                lUpperArm,
                lForeArm,
                lHand,
                rUpperArm,
                rForeArm,
                rHand,
                lThigh,
                lShin,
                lFoot,
                rThigh,
                rShin,
                rFoot,
                rightHandSocket
        );
    }
}