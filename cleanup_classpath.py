import os
import glob
import xml.etree.ElementTree as ET

def get_workspace_projects(roots):
    """Returns a set of project names (directory names) in the workspace roots."""
    projects = set()
    for root in roots:
        expanded_root = os.path.expanduser(root)
        if not os.path.exists(expanded_root):
            continue
        for name in os.listdir(expanded_root):
            if os.path.isdir(os.path.join(expanded_root, name)):
                # Check for .project or src to verify it's a project
                if os.path.exists(os.path.join(expanded_root, name, ".project")) or \
                   os.path.exists(os.path.join(expanded_root, name, "src")):
                    projects.add(name)
    return projects

def cleanup_classpath(classpath_path, source_projects):
    """Surgically removes conflicting lib entries from a .classpath file."""
    try:
        tree = ET.parse(classpath_path)
        root = tree.getroot()
        changed = False
        
        to_remove = []
        for entry in root.findall("classpathentry"):
            kind = entry.get("kind")
            path = entry.get("path", "")
            
            if kind == "lib":
                filename = os.path.basename(path)
                
                # Rule 1: Remove if it matches a source project name (e.g., com.archimatetool.editor-*.jar)
                # We check if any project name is a prefix of the JAR name (before the version/extension)
                for project in source_projects:
                    # Match exact project name or project name followed by version dash
                    if filename == f"{project}.jar" or filename.startswith(f"{project}-") or filename.startswith(f"{project}_"):
                        print(f"  Removing conflict: {filename} (matches source project {project})")
                        to_remove.append(entry)
                        changed = True
                        break
                
                # Rule 2: Remove .source files which often cause issues in VS Code
                if (".source_" in filename or ".source-" in filename) and entry not in to_remove:
                    print(f"  Removing source JAR: {filename}")
                    to_remove.append(entry)
                    changed = True

        for entry in to_remove:
            root.remove(entry)
            
        if changed:
            ET.indent(tree, space="    ")
            tree.write(classpath_path, encoding="UTF-8", xml_declaration=True)
            return True
        return False
    except Exception as e:
        print(f"Error processing {classpath_path}: {e}")
        return False

def main():
    workspace_roots = [
        "/home/rolfmadsen/Github/archi",
        "/home/rolfmadsen/Github/archimatetool-duplicate-merge-plugin",
        "/home/rolfmadsen/Github/archimatetool-elk-auto-layout-plugin"
    ]
    
    print("Identifying source projects in workspace...")
    source_projects = get_workspace_projects(workspace_roots)
    print(f"Found {len(source_projects)} source projects.")
    
    # Also find all .classpath files in the same roots
    all_classpaths = []
    for root in workspace_roots:
        all_classpaths.extend(glob.glob(os.path.join(root, "**/.classpath"), recursive=True))
    
    print(f"Found {len(all_classpaths)} .classpath files to check.")
    
    success_count = 0
    for cp in all_classpaths:
        if cleanup_classpath(cp, source_projects):
            print(f"Cleaned up: {cp}")
            success_count += 1
            
    print(f"\nFinished! Cleaned up {success_count} files.")
    print("IMPORTANT: In VS Code, run 'Java: Clean Java Language Server Workspace' now.")

if __name__ == "__main__":
    main()
