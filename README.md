# ZBuffer

A software-rendered 3D engine built in Java. This project aims to implement the core features of a 3D engine, such as rendering objects, handling user input, and camera movement. The engine is designed with optimization in mind, including caching and vertex simplification for better performance.

## Features
- **Basic 3D Rendering**: Render 3D objects, including full models and wireframes.
- **Camera Movement**: Implement camera controls for navigating the 3D environment.
- **Input Handling**: Handle keyboard and mouse input for user interaction.
- **Optimized Performance**: Includes caching for trigonometric values, vertex transformation, and distance-based vertex simplification.
- **Particle System**: Add particles in the view.
- **Flight Mode**: Toggle between flight and regular movement mode.

## Requirements
- Java 8 or later
- IDE (e.g., IntelliJ IDEA, Eclipse) or command-line tools to compile and run Java applications.

## Setup Instructions
1. Clone the repository:
    ```bash
    git clone https://github.com/OliverEvans08/ZBuffer
    ```

2. Navigate to the project directory:
    ```bash
    cd 3d-engine
    ```

3. Compile and run the project:
    - If using an IDE, import the project and run the main class.
    - If using the command line:
      ```bash
      javac -d bin src/*.java
      java -cp bin engine.Main
      ```

## Controls
- **W**: Move forward
- **S**: Move backward
- **A**: Move left
- **D**: Move right
- **Space**: Ascend (if in flight mode) / Jump (if not in flight mode)
- **Shift**: Descend (if in flight mode)
- **F**: Add particle at the camera's location
- **G**: Toggle flight mode
- **P**: Open/Close the GUI (ClickGUI)

## Future Features
- Improved particle system
- Object interactions (collision detection, etc.)
- Lighting system
- Shader support

## License
This project is licensed under the MIT License - see the LICENSE file for details.
