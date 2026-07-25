package objects.dynamic;

final class BodyPose {

    final PoseBase pPelvis;
    final PoseBase pTorso;
    final PoseBase pNeck;
    final PoseBase pHead;

    final PoseBase pLUA;
    final PoseBase pLFA;
    final PoseBase pLH;
    final PoseBase pRUA;
    final PoseBase pRFA;
    final PoseBase pRH;

    final PoseBase pLT;
    final PoseBase pLS;
    final PoseBase pLF;
    final PoseBase pRT;
    final PoseBase pRS;
    final PoseBase pRF;

    BodyPose(BodyParts parts) {
        pPelvis = new PoseBase(parts.pelvis);
        pTorso = new PoseBase(parts.torso);
        pNeck = new PoseBase(parts.neck);
        pHead = new PoseBase(parts.head);

        pLUA = new PoseBase(parts.lUpperArm);
        pLFA = new PoseBase(parts.lForeArm);
        pLH = new PoseBase(parts.lHand);

        pRUA = new PoseBase(parts.rUpperArm);
        pRFA = new PoseBase(parts.rForeArm);
        pRH = new PoseBase(parts.rHand);

        pLT = new PoseBase(parts.lThigh);
        pLS = new PoseBase(parts.lShin);
        pLF = new PoseBase(parts.lFoot);

        pRT = new PoseBase(parts.rThigh);
        pRS = new PoseBase(parts.rShin);
        pRF = new PoseBase(parts.rFoot);
    }
}