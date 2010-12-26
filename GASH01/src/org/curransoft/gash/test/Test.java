package org.curransoft.gash.test;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

import org.ivpr.ash.ASH;
import org.ivpr.ash.ASHPlugin;
import org.ivpr.ash.ASHPluginLoader;
import org.ivpr.ash.ASHResource;
import org.ivpr.ash.ASHResourceAdapter;
import org.ivpr.ash.ASHTransaction;
import org.ivpr.ash.ASHUtils;
import org.ivpr.ash.UnknownPluginTypeException;
import org.ivpr.ash.UnknownPropertyException;
import org.ivpr.igf.Circle;
import org.ivpr.igf.IGF;
import org.ivpr.igf.Panel;

/**
 * An example ASH application supporting undo and redo (it doesn't quite work yet).
 * 
 * @author curran
 * 
 */
public class Test {
	Panel panel = new Panel();

	private static final String CIRCLE = "http://example.com/circle", X = "x",
			Y = "y", R = "r";
	public List<MovableCircle> circles = new ArrayList<MovableCircle>();
	public boolean graphicsDirty = true;

	public Test() {
		IGF igf = IGF.getInstance();
		int frameWidth = 640;
		int frameHeight = 480;
		igf.showFrame("Hello", 100, 100, frameWidth, frameHeight);
		panel.getWindow().set(0, frameWidth, frameHeight, 0);
		igf.setRootGraphic(panel);

		final View view = new View();

		ASH.init(new ASHPluginLoader() {
			public ASHPlugin getPlugin(String type) {
				if (type.equals(CIRCLE))
					return new ASHPlugin() {
						public ASHResource createResource(String type, int id) {
							if (type.equals(CIRCLE))
								return new MovableCircle(id);
							else
								throw new UnknownPluginTypeException(type);
						}

						public Collection<String> getResourceTypes() {
							return ASHUtils.toList(CIRCLE);
						}
					};
				else
					return null;
			}
		});

		JFrame frame = new JFrame("ASH Example Application");
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		frame.setBounds(100, 100, 500, 500);
		Controller controller = new Controller();
		view.addMouseListener(controller);
		view.addMouseMotionListener(controller);
		frame.addKeyListener(controller);
		frame.add(view);
		frame.setVisible(true);

		// start the graphics repaint thread
		(new Thread(new Runnable() {
			public void run() {
				while (true) {
					for (MovableCircle circle : circles)
						circle.computeNextLocation();
					if (!view.isPaintingTile() && graphicsDirty) {
						view.repaint();
						graphicsDirty = false;
					}
					try {
						Thread.sleep(16);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		})).start();

		// start the thread that records state transitions for intermediate
		// states of interactions (e.g. during a mouse drag) at a fixed time
		// interval.
		(new Thread(new Runnable() {
			public void run() {
				while (true) {
					ASH.commitStateTransition();
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		})).start();

	}

	public static void main(String[] args) {
		new Test();
	}

	class MovableCircle extends ASHResourceAdapter {
		double pull = 0.02;
		double dampening = 0.8;

		int defaultX = 0, defaultY = 0, defaultR = 20;
		int x = defaultX, y = defaultY, r = defaultR;
		double xCurrent, yCurrent, dx, dy;
		Circle circle;

		public MovableCircle(final int id) {
			super(CIRCLE, id);
			circles.add(this);
			graphicsDirty = true;

			circle = new Circle(defaultX, defaultY, defaultR);
			panel.add(circle);
		}

		public void computeNextLocation() {
			dx += (x - xCurrent) * pull;
			dy += (y - yCurrent) * pull;
			dx *= dampening;
			dy *= dampening;

			xCurrent += dx;
			yCurrent += dy;
			if (((int) xCurrent - x) != 0 || ((int) yCurrent - y) != 0)
				graphicsDirty = true;
			if (circle != null)
				circle.setXY(xCurrent, yCurrent);
		}

		public void set(String property, String value) {
			if (property.equals(X))
				x = Integer.parseInt(value);
			else if (property.equals(Y))
				y = Integer.parseInt(value);
			else
				throw new UnknownPropertyException(property);
			graphicsDirty = true;
		}

		public String get(String property) {
			if (property.equals(X))
				return "" + x;
			else if (property.equals(Y))
				return "" + y;
			else
				throw new UnknownPropertyException(property);
		}

		public Collection<String> listProperties() {
			List<String> properties = new LinkedList<String>();
			if (x != defaultX)
				properties.add(X);
			if (y != defaultY)
				properties.add(Y);
			if (r != defaultR)
				properties.add(R);
			return properties;
		}

		public void destroy() {
			circles.remove(this);
			graphicsDirty = true;
		}

		public void kill() {
			circles.remove(this);
			graphicsDirty = true;
		}

		public void resurrect() {
			circles.add(this);
		}

		public boolean contains(int x, int y) {
			return Math.pow(this.x - x, 2) + Math.pow(this.y - y, 2) < r * r;
		}

		public void draw(Graphics g) {
			int r2 = r * 2;
			g.fillOval(((int) xCurrent) - r, ((int) yCurrent) - r, r2, r2);
		}

		public void removeProperty(String property) {
			if (property.equals(X))
				x = defaultX;
			else if (property.equals(Y))
				y = defaultY;
			else if (property.equals(R))
				r = defaultY;
		}
	}

	@SuppressWarnings("serial")
	class View extends JPanel {
		public void paint(Graphics g) {
			g.setColor(Color.white);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(Color.black);
			synchronized (ASH.getLock()) {
				for (MovableCircle c : circles)
					c.draw(g);
			}
		}
	}

	class Controller extends MouseAdapter implements MouseMotionListener,
			KeyListener {
		private final int NO_CIRCLE = -1;
		/**
		 * When circleBeingDragged != -1, a drag is in progress.
		 */
		int circleBeingDragged = NO_CIRCLE;
		/**
		 * (x1,y1) is the point of the mouse press of a drag
		 */
		int x1, y1;

		public void mouseDragged(MouseEvent e) {
			if (circleBeingDragged != NO_CIRCLE) {
				ASHTransaction tx = ASH.begin();
				tx.set(circleBeingDragged, X, e.getX());
				tx.set(circleBeingDragged, Y, e.getY());
				ASH.commit(tx);
			}
		}

		public void mousePressed(MouseEvent e) {
			int circleUnderPoint = getCircleUnderPoint(e.getX(), e.getY());
			if (circleUnderPoint != NO_CIRCLE)
				circleBeingDragged = circleUnderPoint;
			else {
				ASHTransaction tx = ASH.begin();
				circleBeingDragged = tx.create(CIRCLE);
				tx.set(circleBeingDragged, X, e.getX());
				tx.set(circleBeingDragged, Y, e.getY());
				ASH.commit(tx);
			}
		}

		private int getCircleUnderPoint(int x, int y) {
			for (MovableCircle c : circles)
				if (c.contains(x, y))
					return c.getId();
			return NO_CIRCLE;
		}

		public void mouseReleased(MouseEvent e) {
			ASH.commitStateTransition();
		}

		public void keyPressed(KeyEvent e) {
			boolean ctrlIsDown = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) == KeyEvent.CTRL_DOWN_MASK;
			if (ctrlIsDown && e.getKeyCode() == KeyEvent.VK_Z)
				ASH.undo();
			else if (ctrlIsDown && e.getKeyCode() == KeyEvent.VK_Y)
				ASH.redo();
		}

		public void keyReleased(KeyEvent arg0) {
		}

		public void keyTyped(KeyEvent arg0) {
		}
	}
}
