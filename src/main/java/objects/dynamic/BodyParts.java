package objects.dynamic;

import objects.GameObject;

final class BodyParts {

    final BoxPart pelvis;
    final CapsulePart torso;
    final CapsulePart neck;
    final SpherePart head;

    final CapsulePart lUpperArm;
    final CapsulePart lForeArm;
    final SpherePart lHand;

    final CapsulePart rUpperArm;
    final CapsulePart rForeArm;
    final SpherePart rHand;

    final CapsulePart lThigh;
    final CapsulePart lShin;
    final BoxPart lFoot;

    final CapsulePart rThigh;
    final CapsulePart rShin;
    final BoxPart rFoot;

    final GameObject rightHandSocket;

    BodyParts(
            BoxPart pelvis,
            CapsulePart torso,
            CapsulePart neck,
            SpherePart head,
            CapsulePart lUpperArm,
            CapsulePart lForeArm,
            SpherePart lHand,
            CapsulePart rUpperArm,
            CapsulePart rForeArm,
            SpherePart rHand,
            CapsulePart lThigh,
            CapsulePart lShin,
            BoxPart lFoot,
            CapsulePart rThigh,
            CapsulePart rShin,
            BoxPart rFoot,
            GameObject rightHandSocket
    ) {
        this.pelvis = pelvis;
        this.torso = torso;
        this.neck = neck;
        this.head = head;

        this.lUpperArm = lUpperArm;
        this.lForeArm = lForeArm;
        this.lHand = lHand;

        this.rUpperArm = rUpperArm;
        this.rForeArm = rForeArm;
        this.rHand = rHand;

        this.lThigh = lThigh;
        this.lShin = lShin;
        this.lFoot = lFoot;

        this.rThigh = rThigh;
        this.rShin = rShin;
        this.rFoot = rFoot;

        this.rightHandSocket =
                rightHandSocket;
    }

    void setVisible(boolean visible) {
        pelvis.setVisible(visible);
        torso.setVisible(visible);
        neck.setVisible(visible);
        head.setVisible(visible);

        lUpperArm.setVisible(visible);
        lForeArm.setVisible(visible);
        lHand.setVisible(visible);

        rUpperArm.setVisible(visible);
        rForeArm.setVisible(visible);
        rHand.setVisible(visible);

        lThigh.setVisible(visible);
        lShin.setVisible(visible);
        lFoot.setVisible(visible);

        rThigh.setVisible(visible);
        rShin.setVisible(visible);
        rFoot.setVisible(visible);
    }
}