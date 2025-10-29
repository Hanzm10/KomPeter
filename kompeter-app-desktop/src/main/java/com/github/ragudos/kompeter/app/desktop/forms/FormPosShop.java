/*
*
* MIT License
* Authors: Aaron Ragudos, Peter Dela Cruz, Hanz Mapua, Jerick Remo
* (C) 2025
*
*/
package com.github.ragudos.kompeter.app.desktop.forms;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import com.formdev.flatlaf.FlatClientProperties;
import com.github.ragudos.kompeter.app.desktop.assets.AssetLoader;
import com.github.ragudos.kompeter.app.desktop.components.ImagePanel;
import com.github.ragudos.kompeter.app.desktop.components.icons.SVGIconUIColor;
import com.github.ragudos.kompeter.app.desktop.layout.ResponsiveLayout;
import com.github.ragudos.kompeter.app.desktop.layout.ResponsiveLayout.JustifyContent;
import com.github.ragudos.kompeter.app.desktop.system.Form;
import com.github.ragudos.kompeter.app.desktop.utilities.SystemForm;
import com.github.ragudos.kompeter.database.dto.inventory.InventoryMetadataDto;
import com.github.ragudos.kompeter.database.dto.inventory.ItemBrandDto;
import com.github.ragudos.kompeter.database.dto.inventory.ItemCategoryDto;
import com.github.ragudos.kompeter.inventory.InventoryException;
import com.github.ragudos.kompeter.inventory.ItemService;
import com.github.ragudos.kompeter.pointofsale.Cart;
import com.github.ragudos.kompeter.pointofsale.Cart.CartEvent;
import com.github.ragudos.kompeter.pointofsale.CartItem;
import com.github.ragudos.kompeter.pointofsale.InsufficientStockException;
import com.github.ragudos.kompeter.pointofsale.NegativeQuantityException;
import com.github.ragudos.kompeter.utilities.Debouncer;
import com.github.ragudos.kompeter.utilities.HtmlUtils;
import com.github.ragudos.kompeter.utilities.StringUtils;

import net.miginfocom.swing.MigLayout;
import raven.modal.component.DropShadowBorder;

@SystemForm(name = "Point of Sale Shop", description = "The point of sale shop", tags = {"sales", "shop"})
public class FormPosShop extends Form {
    private static final double SIMILARITY_SEARCH_THRESHOLD = 0.7;
    private ArrayList<JCheckBoxMenuItem> brandCheckBoxes;
    private AtomicReference<ArrayList<String>> brandFilters;
    private AtomicReference<Cart> cart;
    private JPanel cartButtonsContainer;
    private JPanel cartContentContainer;
    private JPanel cartMetadataContainer;
    private JPanel cartPanel;
    private JPanel cartTotalPriceContainer;
    private JLabel cartTotalPriceLabel;
    private JPanel cartTotalQuantityContainer;
    private JLabel cartTotalQuantityLabel;
    // No need atomic reference since we'll always access these in EDT
    private ArrayList<JCheckBoxMenuItem> categoryCheckBoxes;
    private AtomicReference<ArrayList<String>> categoryFilters;
    private JButton checkoutButton;
    private JButton clearCartButton;
    private JSplitPane containerSplitPane;
    private Debouncer debouncer;
    private JButton filterButtonTrigger;
    private JPopupMenu filterPopupMenu;
    private JaroWinklerSimilarity fuzzySimilarity;
    private AtomicBoolean isFetching;
    private AtomicReference<ArrayList<InventoryMetadataDto>> items;
    private JPanel leftPanel;
    private JPanel leftPanelContentContainer;
    private JPanel leftPanelHeader;
    private JPanel rightPanel;

    private JTextField searchTextField;

    @Override
    public boolean formBeforeClose() {
        if (cart.getAcquire().isEmpty()) {
            return true;
        }

        int chosenOption = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(this),
                "Cart is not empty. Would you like to save the current cart's state?", "Save or Remove",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);

        boolean returnVal = false;

        switch (chosenOption) {
            case JOptionPane.CANCEL_OPTION :
                returnVal = false;
                break;
            case JOptionPane.YES_OPTION :
                returnVal = true;
                break;
            case JOptionPane.NO_OPTION :
                SwingUtilities.invokeLater(() -> {
                    cart.getAcquire().clearCart();
                    buildRightPanelContent();
                });
                returnVal = true;
                break;
        }

        return returnVal;
    }

    @Override
    public boolean formBeforeLogout() {
        if (cart.getAcquire().isEmpty()) {
            return true;
        }

        int chosenOption = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(this),
                "Cart is not empty. Would you like to continue logging out?", "Log out?", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        return chosenOption == JOptionPane.YES_OPTION;
    }

    @Override
    public void formInit() {
        init();
        formRefresh();
    }

    @Override
    public void formOpen() {
    }

    @Override
    public void formRefresh() {
        if (isFetching.get()) {
            return;
        }

        new Thread(this::loadData, "Load Point of Sale Shop Data").start();
    }

    private void applyShadowBorder(JPanel panel) {
        if (panel != null) {
            panel.setBorder(new DropShadowBorder(new Insets(2, 4, 8, 4), 1, 16));
        }
    }

    private void buildFilterPopupMenu() {
        removeAllItemListenersOfPopupMenu(filterPopupMenu);
        filterPopupMenu.removeAll();

        JLabel categoryLabel = new JLabel("Category");
        JLabel brandLabel = new JLabel("Brand");

        filterPopupMenu.add(categoryLabel);

        categoryCheckBoxes.forEach((c -> {
            filterPopupMenu.add(c);

            c.addItemListener(new PopupMenuCheckboxItemListener());
        }));

        filterPopupMenu.addSeparator();

        filterPopupMenu.add(brandLabel);

        brandCheckBoxes.forEach((c -> {
            filterPopupMenu.add(c);

            c.addItemListener(new PopupMenuCheckboxItemListener());
        }));
    }

    private void buildLeftPanelContent() {
        leftPanelContentContainer.removeAll();

        ResponsiveLayout layout = (ResponsiveLayout) leftPanelContentContainer.getLayout();

        List<InventoryMetadataDto> items = filterItems();

        if (items.isEmpty()) {
            layout.setJustifyContent(JustifyContent.CENTER);
            leftPanelContentContainer.add(new NoResultsPanel());

            leftPanelContentContainer.repaint();
            leftPanelContentContainer.revalidate();

            return;
        }

        layout.setJustifyContent(JustifyContent.START);

        for (InventoryMetadataDto item : items) {
            JPanel itemPanel = new JPanel(new BorderLayout()) {
                @Override
                public void updateUI() {
                    super.updateUI();
                    applyShadowBorder(this);
                }
            };

            JPanel itemContentContainer = new JPanel(new MigLayout("flowx, wrap, insets 0", "[grow, fill, center]"));

            String imagePath = item.displayImage() == null || item.displayImage().isEmpty()
                    ? "Acer Nitro 5 Laptop.png"
                    : item.displayImage();

            ImagePanel imagePanel = new ImagePanel(AssetLoader.loadImage(imagePath, true));
            JLabel itemName = new JLabel(HtmlUtils.wrapInHtml(String.format("<p align='center'>%s", item.itemName())));
            JLabel itemPrice = new JLabel(String.format(HtmlUtils.wrapInHtml("<p align='center'> %s"),
                    StringUtils.formatDouble(item.itemPricePhp())));

            itemName.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");
            itemPrice.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");

            itemName.setHorizontalAlignment(JLabel.CENTER);
            itemPrice.setHorizontalAlignment(JLabel.CENTER);

            itemContentContainer.add(imagePanel, "grow");
            itemContentContainer.add(itemName, "growx, gaptop 6px");
            itemContentContainer.add(itemPrice, "growx, gaptop 2px");

            itemPanel.add(itemContentContainer);

            leftPanelContentContainer.add(itemPanel, BorderLayout.CENTER);

            itemPanel.addMouseListener(new ItemPanelMouseListener(item));
        }

        leftPanelContentContainer.repaint();
        leftPanelContentContainer.revalidate();
    }

    private void buildRightPanelContent() {
        Cart cart = this.cart.getAcquire();

        removeActionListeners(cartPanel);
        cartPanel.removeAll();

        if (cart.isEmpty()) {
            rightPanel.remove(cartButtonsContainer);
            cartContentContainer.remove(cartMetadataContainer);

            cartPanel.add(new NoItemsInCartPanel());

            rightPanel.repaint();
            rightPanel.revalidate();

            return;
        }

        createRightPanelContents();
    }

    private void createContainers() {
        containerSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        leftPanel = new JPanel(new MigLayout("insets 0, flowy, al center center", "[grow, fill]",
                "[top]16px[top]4px[top]8px[grow,fill]"));
        JPanel rightPanelWrapper = new JPanel(
                new MigLayout("insets 0, al center center", "[grow, fill, center]", "[grow, fill, center]"));
        rightPanel = new JPanel(new MigLayout("insets 0, wrap", "[grow, fill]", "[grow, fill, top][bottom]"));
        leftPanelHeader = new JPanel(new MigLayout("flowx, insets 0 0 0 12", "[grow,fill]16px[]2px[]push[]"));
        leftPanelContentContainer = new JPanel(
                new ResponsiveLayout(JustifyContent.CENTER, new Dimension(190, -1), 1, 1));
        JLabel title = new JLabel("Products");
        JLabel subtitle = new JLabel("Click a product card to add them to cart.");

        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "primary h2");
        subtitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted h3");

        JScrollPane scroller = new JScrollPane(leftPanelContentContainer);

        leftPanel.putClientProperty(FlatClientProperties.STYLE, "background:tint($Panel.background, 20%);");

        scroller.getHorizontalScrollBar().putClientProperty(FlatClientProperties.STYLE,
                "" + "trackArc:$ScrollBar.thumbArc;" + "thumbInsets:0,0,0,0;" + "width:9;");
        scroller.getVerticalScrollBar().putClientProperty(FlatClientProperties.STYLE,
                "" + "trackArc:$ScrollBar.thumbArc;" + "thumbInsets:0,0,0,0;" + "width:9;");
        scroller.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        containerSplitPane.setResizeWeight(0.7);
        containerSplitPane.setContinuousLayout(true);
        containerSplitPane.setOneTouchExpandable(true);

        scroller.getVerticalScrollBar().setUnitIncrement(16);
        scroller.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroller.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        leftPanel.add(leftPanelHeader, "growx");
        leftPanel.add(title, "growx");
        leftPanel.add(subtitle, "growx");
        leftPanel.add(scroller, "grow");

        rightPanelWrapper.add(rightPanel, "grow, width ::450px");

        containerSplitPane.add(leftPanel);
        containerSplitPane.add(rightPanelWrapper);

        add(containerSplitPane);
    }

    private Component createItemCard(CartItem item) {
        JPanel cartItemPanel = new JPanel(new MigLayout("insets 5, fillx", "[grow][right]", "[]"));
        cartItemPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.DARK_GRAY));
        cartItemPanel.setName(String.format("_itemStockId:%s", item._itemStockId()));

        JLabel productName = new JLabel(item.productName());
        JLabel productPrice = new JLabel(String.format("%s", StringUtils.formatDouble(item.price())));
        JLabel productQty = new JLabel(String.format("x%s", item.qty()));

        String decIcon = item.qty() == 1 ? "trash.svg" : "minus.svg";

        JPanel qtyPanel = new JPanel(new MigLayout("insets 0, wrap 3, al center center", "[]5[]5[]", "[]"));
        JButton decBtn = new JButton(new SVGIconUIColor(decIcon, 0.5f, "foreground.background"));
        JButton addBtn = new JButton(new SVGIconUIColor("plus.svg", 0.5f, "foreground.background"));

        decBtn.setName("decrement");
        addBtn.setName("increment");
        productPrice.setName("price");
        productQty.setName("quantity");

        decBtn.putClientProperty(FlatClientProperties.STYLE_CLASS, "ghost");
        addBtn.putClientProperty(FlatClientProperties.STYLE_CLASS, "ghost");
        decBtn.putClientProperty(FlatClientProperties.STYLE, "arc:999;");
        addBtn.putClientProperty(FlatClientProperties.STYLE, "arc:999;");

        addBtn.addActionListener(new IncrementItemQuantityCartActionListener(item));
        decBtn.addActionListener(new DecrementItemQuantityCartActionListener(item));

        qtyPanel.add(decBtn, "center");
        qtyPanel.add(productQty, "center");
        qtyPanel.add(addBtn, "center");

        cartItemPanel.add(productName, "growx");
        cartItemPanel.add(productPrice, "gapx 10");
        cartItemPanel.add(qtyPanel, "gapx 10");

        return cartItemPanel;
    }

    private void createLeftPanel() {
        searchTextField = new JTextField();

        searchTextField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
                new SVGIconUIColor("search.svg", 0.5f, "TextField.placeholderForeground"));
        searchTextField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search products...");

        filterButtonTrigger = new JButton(new SVGIconUIColor("filter.svg", 0.5f, "foreground.muted"));

        filterButtonTrigger.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        filterPopupMenu = new JPopupMenu();

        buildFilterPopupMenu();

        filterButtonTrigger.addActionListener(new FilterButtonTriggerActionListener());
        searchTextField.getDocument().addDocumentListener(new SearchTextFieldDocumentListener());

        buildLeftPanelContent();

        leftPanelHeader.add(searchTextField);
        leftPanelHeader.add(filterButtonTrigger);
    }

    private void createRightPanel() {
        JPanel container = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                applyShadowBorder(this);
            }
        };

        cartContentContainer = new JPanel(new MigLayout("insets 0, flowx, wrap", "[grow, fill]"));
        JLabel title = new JLabel("Cart", new SVGIconUIColor("shopping-cart.svg", 0.75f, "color.primary"),
                JLabel.RIGHT);
        cartPanel = new JPanel(new MigLayout("insets 0, flowx, wrap, al center top", "[grow, fill, center]"));
        JScrollPane scroller = new JScrollPane(cartPanel);
        checkoutButton = new JButton("Checkout");
        clearCartButton = new JButton("Clear Cart");
        cartMetadataContainer = new JPanel(
                new MigLayout("insets 0 6 6 6, wrap, flowx, gapy 3px", "[grow, fill, center]"));
        cartButtonsContainer = new JPanel(new MigLayout("insets 0 4 4 4, fillx, gapx 9px", "[grow, fill, center]"));
        cartTotalPriceContainer = new JPanel(new MigLayout("insets 0, flowx"));
        cartTotalQuantityContainer = new JPanel(new MigLayout("insets 0, flowx"));
        cartTotalPriceLabel = new JLabel("0.00");
        cartTotalQuantityLabel = new JLabel("");
        JLabel totalPriceTitle = new JLabel("Total: ");
        JLabel totalQuantityTitle = new JLabel("Quantity: ");

        title.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
        title.setIconTextGap(16);
        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "primary h1");
        title.setHorizontalAlignment(JLabel.LEFT);

        rightPanel.putClientProperty(FlatClientProperties.STYLE, "background:tint($Panel.background, 25%);");

        scroller.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroller.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        scroller.getHorizontalScrollBar().setUnitIncrement(16);
        scroller.getHorizontalScrollBar().putClientProperty(FlatClientProperties.STYLE,
                "" + "trackArc:$ScrollBar.thumbArc;" + "thumbInsets:0,0,0,0;" + "width:9;");
        scroller.getVerticalScrollBar().putClientProperty(FlatClientProperties.STYLE,
                "" + "trackArc:$ScrollBar.thumbArc;" + "thumbInsets:0,0,0,0;" + "width:9;");
        scroller.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        cartPanel.putClientProperty(FlatClientProperties.STYLE, "background: null;");
        cartButtonsContainer.putClientProperty(FlatClientProperties.STYLE, "background: null;");
        cartTotalPriceContainer.putClientProperty(FlatClientProperties.STYLE, "background: null;");
        cartTotalQuantityContainer.putClientProperty(FlatClientProperties.STYLE, "background: null;");
        cartMetadataContainer.putClientProperty(FlatClientProperties.STYLE, "background: null;");

        cartMetadataContainer.setBorder(
                BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.DARK_GRAY),
                        BorderFactory.createEmptyBorder(5, 0, 0, 0)));

        totalPriceTitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");
        totalQuantityTitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");
        cartTotalPriceLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");
        cartTotalQuantityLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");

        clearCartButton.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted h3");
        checkoutButton.putClientProperty(FlatClientProperties.STYLE_CLASS, "primary h3");

        cartTotalPriceContainer.add(totalPriceTitle, "pushx");
        cartTotalPriceContainer.add(cartTotalPriceLabel);

        cartTotalQuantityContainer.add(totalQuantityTitle, "pushx");
        cartTotalQuantityContainer.add(cartTotalQuantityLabel);

        cartMetadataContainer.add(cartTotalQuantityContainer, "growx");
        cartMetadataContainer.add(cartTotalPriceContainer, "growx");

        cartButtonsContainer.add(clearCartButton, "growx");
        cartButtonsContainer.add(checkoutButton, "growx");

        cartContentContainer.add(title);
        cartContentContainer.add(scroller, "grow, pushy");

        buildRightPanelContent();

        container.add(cartContentContainer, BorderLayout.CENTER);

        rightPanel.add(container, "grow");

        checkoutButton.addActionListener(new CheckoutButtonActionListener(this));
        clearCartButton.addActionListener(new ClearCartActionListener());
    }

    private void createRightPanelContents() {
        Cart cart = this.cart.getAcquire();

        JPanel headerPanel = new JPanel(new MigLayout("insets 5, fillx", "[grow][right][center]", "[]"));
        headerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.DARK_GRAY));

        JLabel nameHeader = new JLabel("Product");
        JLabel priceHeader = new JLabel("Price");
        JLabel quantityHeader = new JLabel("Quantity");

        nameHeader.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        priceHeader.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        quantityHeader.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");

        headerPanel.add(nameHeader, "growx");
        headerPanel.add(priceHeader, "gapright 20");
        headerPanel.add(quantityHeader, "gapright 6");

        cartPanel.add(headerPanel, "growx");

        for (CartItem item : cart.getAllItems()) {
            cartPanel.add(createItemCard(item), "growx, wrap");
        }

        cartTotalPriceLabel.setText(String.format("%s", cart.totalPrice()));
        cartTotalQuantityLabel.setText(String.format("%s", cart.totalQuantity()));

        cartContentContainer.add(cartMetadataContainer);

        rightPanel.add(cartButtonsContainer, "growx");
        rightPanel.repaint();
        rightPanel.revalidate();
    }

    private List<InventoryMetadataDto> filterItems() {
        String searchQuery = searchTextField.getText();
        ArrayList<String> brandFilters = this.brandFilters.getAcquire();
        ArrayList<String> categoryFilters = this.categoryFilters.getAcquire();

        if (searchQuery.isEmpty() && brandFilters.isEmpty() && categoryFilters.isEmpty()) {
            return items.getAcquire();
        }

        return items.getAcquire().stream().filter((item) -> {
            double similarity = searchQuery.isEmpty()
                    ? SIMILARITY_SEARCH_THRESHOLD
                    : fuzzySimilarity.apply(item.itemName(), searchQuery);
            boolean isInBrandScope = brandFilters.isEmpty() || brandFilters.contains(item.brandName());
            boolean isInCategoryScope = categoryFilters.isEmpty() || categoryFilters.contains(item.categoryName());

            return similarity >= SIMILARITY_SEARCH_THRESHOLD && isInBrandScope && isInCategoryScope;
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    private Component getComponent(JComponent parent, String name) {
        if (parent == null) {
            return null;
        }

        for (Component c : parent.getComponents()) {
            if (c.getName() != null && c.getName().equals(name)) {
                return c;
            }

            if (c instanceof JComponent jc) {
                Component val = getComponent(jc, name);

                if (val != null) {
                    return val;
                }
            }
        }

        return null;
    }

    private void init() {
        isFetching = new AtomicBoolean(false);
        cart = new AtomicReference<>(new Cart());
        categoryFilters = new AtomicReference<>(new ArrayList<>());
        brandFilters = new AtomicReference<>(new ArrayList<>());
        items = new AtomicReference<>(new ArrayList<>());
        categoryCheckBoxes = new ArrayList<>();
        brandCheckBoxes = new ArrayList<>();
        fuzzySimilarity = new JaroWinklerSimilarity();
        debouncer = new Debouncer(250);
        cart.getAcquire().subscribe(new CartConsumer());

        setLayout(new BorderLayout());

        createContainers();
        createLeftPanel();
        createRightPanel();
    }

    private void loadData() {
        isFetching.set(true);

        SwingUtilities.invokeLater(() -> {
            leftPanelContentContainer.removeAll();

            ((ResponsiveLayout) leftPanelContentContainer.getLayout()).setJustifyContent(JustifyContent.CENTER);

            leftPanelContentContainer.add(new LoadingPanel());
            leftPanelContentContainer.repaint();
            leftPanelContentContainer.revalidate();
        });

        ItemService itemService = new ItemService();

        try {
            List<ItemBrandDto> itemBrands = itemService.showAllBrands();
            List<ItemCategoryDto> itemCategories = itemService.showAllCategories();
            List<InventoryMetadataDto> items = itemService.getAllItems();

            this.items.set((ArrayList<InventoryMetadataDto>) items);

            SwingUtilities.invokeLater(() -> {
                brandCheckBoxes.clear();
                categoryCheckBoxes.clear();

                ArrayList<String> brandFilters = this.brandFilters.getAcquire();
                ArrayList<String> categoryFilters = this.categoryFilters.getAcquire();

                itemBrands.forEach((brand) -> {
                    JCheckBoxMenuItem c = new JCheckBoxMenuItem(brand.name());

                    if (brandFilters.contains(brand.name())) {
                        c.setSelected(true);
                    }

                    c.setName("brand");

                    brandCheckBoxes.add(c);
                });

                itemCategories.forEach((category) -> {
                    JCheckBoxMenuItem c = new JCheckBoxMenuItem(category.name());

                    if (categoryFilters.contains(category.name())) {
                        c.setSelected(true);
                    }

                    c.setName("category");

                    categoryCheckBoxes.add(c);
                });

                SwingUtilities.invokeLater(() -> {
                    buildFilterPopupMenu();
                    buildLeftPanelContent();
                });

                isFetching.set(false);
            });
        } catch (InventoryException err) {
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this), err.getMessage(),
                    "Failed to load data :(", JOptionPane.ERROR_MESSAGE);

            SwingUtilities.invokeLater(() -> {
                leftPanelContentContainer.removeAll();

                ((ResponsiveLayout) leftPanelContentContainer.getLayout()).setJustifyContent(JustifyContent.CENTER);

                leftPanelContentContainer.add(new ErrorPanel());
                leftPanelContentContainer.repaint();
                leftPanelContentContainer.revalidate();
            });

            isFetching.set(false);
        }
    }

    private void removeActionListeners(JComponent component) {
        for (Component c : component.getComponents()) {
            switch (c) {
                case JButton button :
                    Arrays.stream(button.getActionListeners()).forEach(button::removeActionListener);
                    break;
                case JComponent co :
                    removeActionListeners(co);
                    break;
                default :
            }
        }
    }

    private void removeAllItemListenersOfPopupMenu(JPopupMenu menu) {
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item) {
                for (ItemListener l : item.getItemListeners()) {
                    item.removeItemListener(l);
                }
            }
        }
    }

    private void search() {
        debouncer.call(() -> {
            SwingUtilities.invokeLater(() -> {
                leftPanelContentContainer.removeAll();

                ((ResponsiveLayout) leftPanelContentContainer.getLayout()).setJustifyContent(JustifyContent.CENTER);

                leftPanelContentContainer.add(new LoadingPanel());
                leftPanelContentContainer.repaint();
                leftPanelContentContainer.revalidate();

                SwingUtilities.invokeLater(() -> {
                    buildLeftPanelContent();
                });
            });
        });
    }

    private void updateCartTotals() {
        Cart acquiredCart = cart.getAcquire();

        cartTotalPriceLabel.setText(String.format("%s", StringUtils.formatDouble(acquiredCart.totalPrice())));
        cartTotalQuantityLabel.setText(String.format("%s", acquiredCart.totalQuantity()));
    }

    private class AddToCartDialog extends JDialog {
        public AddToCartDialog(InventoryMetadataDto item, Window owner) {
            super(owner, "Add to Cart", Dialog.ModalityType.APPLICATION_MODAL);

            putClientProperty(FlatClientProperties.STYLE, "background:tint($Panel.background,20%);");
            setLayout(new MigLayout("insets 9, flowx, wrap", "[grow, fill, center]"));

            JLabel subtitle = new JLabel(HtmlUtils
                    .wrapInHtml(String.format("<p align='center'>This will add %s to the cart.", item.itemName())));

            subtitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");

            JLabel quantityLabel = new JLabel(String.format("Quantity (Max %s)", item.quantity()));
            JSpinner quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, item.quantity(), 1));

            JButton cancelButton = new JButton("Cancel");
            JButton confirmButton = new JButton("Confirm");

            quantityLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");

            cancelButton.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
            confirmButton.putClientProperty(FlatClientProperties.STYLE_CLASS, "primary");

            cancelButton.addActionListener(new CancelButtonActionListener());
            confirmButton.addActionListener(new ConfirmButtonActionListener(this, item, quantitySpinner));

            add(subtitle);

            add(quantityLabel, "gapy 6px");
            add(quantitySpinner, "push, gapy 2px");

            add(cancelButton, "split 2, gapy 20px");
            add(confirmButton, "gapx 9px");

            pack();
            setLocationRelativeTo(owner);
        }

        private class CancelButtonActionListener implements ActionListener {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        }

        private class ConfirmButtonActionListener implements ActionListener {
            private final JDialog addToCartDialog;
            private final InventoryMetadataDto item;
            private final JSpinner numberSpinner;

            public ConfirmButtonActionListener(JDialog addToCartDialog, InventoryMetadataDto item,
                    JSpinner numberSpinner) {
                this.item = item;
                this.numberSpinner = numberSpinner;
                this.addToCartDialog = addToCartDialog;
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                Integer val = (Integer) numberSpinner.getValue();

                Cart cartVal = cart.getAcquire();

                try {
                    if (cartVal.exists(item._itemStockId())) {
                        cartVal.increaseItemQty(item._itemStockId(), val);
                    } else {
                        cartVal.addItem(new CartItem(item._itemStockId(), item.itemName(), item.categoryName(),
                                item.brandName(), item.quantity(), val, item.itemPricePhp()));
                    }
                } catch (InsufficientStockException | NegativeQuantityException err) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(addToCartDialog, err.getMessage(), err.getClass().getName(),
                                JOptionPane.ERROR_MESSAGE);
                    });

                    return;
                }

                dispose();
            }
        }
    }

    private class CartConsumer implements Consumer<CartEvent> {
        @Override
        public void accept(CartEvent cartEvent) {
            Cart acquiredCart = cart.getAcquire();
            CartItem cartItem = cartEvent.payload();
            CartItem previousCartItem = cartEvent.previousPayload();

            SwingUtilities.invokeLater(() -> {
                switch (cartEvent.eventType()) {
                    case INCREASE_ITEM_QTY :
                    case INCREMENT_ITEM :
                    case DECREASE_ITEM_QTY :
                    case DECREMENT_ITEM : {
                        JComponent parent = (JComponent) getComponent(cartPanel,
                                String.format("_itemStockId:%s", cartItem._itemStockId()));
                        JButton decrementButton = (JButton) getComponent(parent, "decrement");
                        JLabel priceLabel = (JLabel) getComponent(parent, "price");
                        JLabel qtyLabel = (JLabel) getComponent(parent, "quantity");

                        switch (cartEvent.eventType()) {
                            case INCREASE_ITEM_QTY :
                            case INCREMENT_ITEM : {
                                if (previousCartItem.qty() == 1) {
                                    decrementButton
                                            .setIcon(new SVGIconUIColor("minus.svg", 0.5f, "foregorund.background"));
                                }

                                priceLabel.setText(
                                        String.format("%s", StringUtils.formatDouble(cartItem.getTotalPrice())));
                                qtyLabel.setText(String.format("x%s", cartItem.qty()));

                                updateCartTotals();
                            }
                                break;
                            case DECREASE_ITEM_QTY :
                            case DECREMENT_ITEM : {
                                if (cartItem.qty() == 1) {
                                    decrementButton
                                            .setIcon(new SVGIconUIColor("trash.svg", 0.5f, "foregorund.background"));
                                }

                                priceLabel.setText(
                                        String.format("%s", StringUtils.formatDouble(cartItem.getTotalPrice())));
                                qtyLabel.setText(String.format("x%s", cartItem.qty()));

                                updateCartTotals();
                            }
                                break;
                            default :
                        }
                    }
                        break;
                    case REMOVE_ITEM : {
                        if (acquiredCart.isEmpty()) {
                            buildRightPanelContent();
                        } else {
                            cartPanel.remove((JComponent) getComponent(cartPanel,
                                    String.format("_itemStockId:%s", cartItem._itemStockId())));
                            cartPanel.repaint();
                            cartPanel.revalidate();

                            updateCartTotals();
                        }
                    }
                        break;
                    case CLEAR : {
                        buildRightPanelContent();
                    }
                        break;
                    case ADD_ITEM : {
                        if (acquiredCart.getAllItems().size() == 1) {
                            buildRightPanelContent();
                        } else {
                            cartPanel.add(createItemCard(cartItem));
                            cartPanel.repaint();
                            cartPanel.revalidate();
                        }

                        updateCartTotals();
                    }
                        break;
                }
            });
        }
    }

    private class CheckoutButtonActionListener implements ActionListener {
        private FormPosShop formPosShop;

        public CheckoutButtonActionListener(FormPosShop formPosShop) {
            this.formPosShop = formPosShop;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
        }
    }

    private class ClearCartActionListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {

            int chosenOption = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(cartButtonsContainer),
                    "Are you sure you want to clear your cart?", "Clear Cart", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            switch (chosenOption) {
                case JOptionPane.YES_OPTION :
                    cart.getAcquire().clearCart();
                    break;
            }
        }
    }

    private class DecrementItemQuantityCartActionListener implements ActionListener {
        private CartItem item;

        public DecrementItemQuantityCartActionListener(CartItem item) {
            this.item = item;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Cart acquiredCart = cart.getAcquire();

            if (item.qty() == 1) {
                acquiredCart.removeItem(item);
            } else {
                try {
                    acquiredCart.decrementItem(item._itemStockId());
                } catch (NegativeQuantityException err) {
                    JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(cartPanel), err.getMessage(),
                            err.getClass().getName(), JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private class ErrorPanel extends JPanel {
        public ErrorPanel() {
            JLabel text = new JLabel(HtmlUtils.wrapInHtml("Something went wrong! :("));

            putClientProperty(FlatClientProperties.STYLE, "background: null;");
            text.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");

            add(text, BorderLayout.CENTER);
        }
    }

    private class FilterButtonTriggerActionListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            filterPopupMenu.show(filterButtonTrigger, 0, filterButtonTrigger.getHeight());
        }
    }

    private class IncrementItemQuantityCartActionListener implements ActionListener {
        private CartItem item;

        public IncrementItemQuantityCartActionListener(CartItem item) {
            this.item = item;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Cart acquiredCart = cart.getAcquire();

            try {
                acquiredCart.incrementItem(item._itemStockId());
            } catch (InsufficientStockException err) {
                JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(cartPanel), err.getMessage(),
                        err.getClass().getName(), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private class ItemPanelMouseListener extends MouseAdapter {
        private InventoryMetadataDto item;

        public ItemPanelMouseListener(InventoryMetadataDto item) {
            this.item = item;
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            new AddToCartDialog(item, SwingUtilities.getWindowAncestor(containerSplitPane)).setVisible(true);
        }
    }

    private class LoadingPanel extends JPanel {
        public LoadingPanel() {
            setLayout(new BorderLayout());

            putClientProperty(FlatClientProperties.STYLE, "background: null;");

            JLabel loading = new JLabel("Loading...");

            loading.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");

            add(loading, BorderLayout.CENTER);
        }
    }

    private class NoItemsInCartPanel extends JPanel {
        public NoItemsInCartPanel() {
            setLayout(new BorderLayout());

            putClientProperty(FlatClientProperties.STYLE, "background: null;");

            JLabel text = new JLabel("No items in cart yet :(");

            text.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");

            add(text, BorderLayout.NORTH);
        }
    }

    private class NoResultsPanel extends JPanel {
        public NoResultsPanel() {
            setLayout(new BorderLayout());

            putClientProperty(FlatClientProperties.STYLE, "background: null;");

            JLabel noResults = new JLabel(HtmlUtils.wrapInHtml("<p align='center'>No results were found :("));

            noResults.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");

            add(noResults, BorderLayout.NORTH);
        }
    }

    private class PopupMenuCheckboxItemListener implements ItemListener {
        @Override
        public void itemStateChanged(ItemEvent e) {
            JCheckBoxMenuItem item = (JCheckBoxMenuItem) e.getItemSelectable();
            String text = item.getText();
            String name = item.getName();

            switch (e.getStateChange()) {
                case ItemEvent.DESELECTED :
                    if (name.equals("brand")) {
                        brandFilters.getAcquire().remove(text);
                    } else if (name.equals("category")) {
                        categoryFilters.getAcquire().remove(text);
                    }
                    break;
                case ItemEvent.SELECTED :
                    if (name.equals("brand")) {
                        brandFilters.getAcquire().add(text);
                    } else if (name.equals("category")) {
                        categoryFilters.getAcquire().add(text);
                    }
                    break;
            }

            search();
        }
    }

    private class SearchTextFieldDocumentListener implements DocumentListener {
        @Override
        public void changedUpdate(DocumentEvent e) {
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            search();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            search();
        }
    }
}
