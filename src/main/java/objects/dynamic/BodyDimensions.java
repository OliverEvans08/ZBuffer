package objects.dynamic;

final class BodyDimensions {

    final double h;
    final double w;

    final double legH;
    final double pelvisH;
    final double torsoH;
    final double neckH;
    final double headR;

    final double thighL;
    final double shinL;
    final double footH;

    final double armH;
    final double uArmL;
    final double fArmL;

    final double handR;
    final double legR;
    final double armR;
    final double torsoR;
    final double neckR;

    final double legTotal;

    BodyDimensions(
            double width,
            double height
    ) {
        h =
                Math.max(1.2, height);

        w =
                Math.max(0.35, width);

        legH = h * 0.52;
        pelvisH = h * 0.10;
        torsoH = h * 0.28;
        neckH = h * 0.05;
        headR = h * 0.10;

        thighL = legH * 0.48;
        shinL = legH * 0.40;

        footH =
                Math.max(
                        0.06,
                        legH - thighL - shinL
                );

        armH = torsoH * 0.92;
        uArmL = armH * 0.52;
        fArmL = armH * 0.48;

        handR = w * 0.12;
        legR = w * 0.12;
        armR = w * 0.10;
        torsoR = w * 0.22;
        neckR = w * 0.11;

        legTotal =
                thighL + shinL + footH;
    }
}